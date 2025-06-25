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
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
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
        fun onSettingsRequested(instanceId: Int)
        fun onLockToggled(instanceId: Int)
        fun onCloseRequested(instanceId: Int)
        fun onBillboardTapped(instanceId: Int) // To request image change
        fun onColorChangeRequested(instanceId: Int) // Placeholder for color picker
        fun onControlsToggled(instanceId: Int) // To toggle controls visibility
    }

    var interactionListener: InteractionListener? = null
    private var currentState: ScreenMaskState

    // Control buttons
    private var billboardImageView: ImageView
    private var closeButton: ImageView
    private val topLeftResizeHandle: ImageView = ImageView(context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_resize_left_handle))
        val handleSize = dpToPx(48) // Make handles large enough to grab easily
        layoutParams = LayoutParams(handleSize, handleSize).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }
    private val bottomRightResizeHandle: ImageView = ImageView(context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_resize_right_handle))
        val handleSize = dpToPx(48)
        layoutParams = LayoutParams(handleSize, handleSize).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        }
    }
    private val settingsButton: ImageView = ImageView(context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_settings))
        val buttonSize = dpToPx(32)
        setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        layoutParams = LayoutParams(buttonSize, buttonSize).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        }
    }

    // Border styling
    private var yellowBorder: GradientDrawable
    private var lockBorder: GradientDrawable
    private var isLockedByLockAll = false
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
    private val touchSlop = dpToPx(10)
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

    // Mask stamp icon in center
    private val maskStampImageView: ImageView = ImageView(context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_mask_stamp))
        scaleType = ImageView.ScaleType.FIT_CENTER
        alpha = 0.3f // Semi-transparent as it's a watermark-style stamp
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
    }

    init {
        // Set initial background (will be updated by state)
        setBackgroundColor(Color.BLACK) // Or any other opaque default you prefer

        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        setupMaskTouchListener()

        // Add resize handles
        addView(topLeftResizeHandle)
        addView(bottomRightResizeHandle)

        // Add settings button
        addView(settingsButton)
        settingsButton.setOnClickListener {
            if (!currentState.isLocked) {
                // Apply active state color
                settingsButton.setColorFilter(Color.parseColor("#808080"), PorterDuff.Mode.SRC_IN)
                interactionListener?.onSettingsRequested(instanceId)

                // Reset color after a delay (will be reset anyway when activity closes)
                postDelayed({
                    settingsButton.clearColorFilter()
                }, 500)
            }
        }

        // Add mask stamp before other views so it's behind
        addView(maskStampImageView, 0) // Insert at index 0 to be behind other elements

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
                    showMessage(context.getString(R.string.unlock_mask_to_close))
                }
            }
        }
        addView(closeButton)

        // Red border for locked state
        lockBorder = GradientDrawable().apply {
            setStroke(dpToPx(3), Color.RED)  // Change to RED as specified
            setColor(Color.TRANSPARENT)
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

        // Hide resize handles when locked
        val handleVisibility = if (newState.isLocked || !newState.isControlsVisible) GONE else VISIBLE
        topLeftResizeHandle.visibility = handleVisibility
        bottomRightResizeHandle.visibility = handleVisibility

        // Hide mask stamp when billboard is visible
        maskStampImageView.visibility = if (newState.isBillboardVisible) GONE else VISIBLE

        // Update border visibility based on lock state
        if (newState.isLocked) {
            // Start fade-in then fade-out animation
            foreground = lockBorder // Set border as foreground to overlay content
            lockBorder.alpha = 255
            borderFadeAnimator?.cancel()
            borderFadeAnimator = ObjectAnimator.ofInt(lockBorder, "alpha", 255, 0).apply {
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

                    // Update handle visual state
                    when (currentResizeDirection) {
                        ResizeDirection.TOP_LEFT -> {
                            topLeftResizeHandle.setImageDrawable(
                                ContextCompat.getDrawable(context, R.drawable.ic_resize_left_handle_active)
                            )
                        }
                        ResizeDirection.BOTTOM_RIGHT -> {
                            bottomRightResizeHandle.setImageDrawable(
                                ContextCompat.getDrawable(context, R.drawable.ic_resize_right_handle_active)
                            )
                        }
                        else -> { /* No visual change for move */ }
                    }

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

                    // Reset handle visuals to default
                    topLeftResizeHandle.setImageDrawable(
                        ContextCompat.getDrawable(context, R.drawable.ic_resize_left_handle)
                    )
                    bottomRightResizeHandle.setImageDrawable(
                        ContextCompat.getDrawable(context, R.drawable.ic_resize_right_handle)
                    )
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
        // Check if touching a resize handle specifically
        val hitRect = android.graphics.Rect()

        topLeftResizeHandle.getHitRect(hitRect)
        if (topLeftResizeHandle.isVisible && hitRect.contains(localX.toInt(), localY.toInt())) {
            return ResizeDirection.TOP_LEFT
        }

        bottomRightResizeHandle.getHitRect(hitRect)
        if (bottomRightResizeHandle.isVisible && hitRect.contains(localX.toInt(), localY.toInt())) {
            return ResizeDirection.BOTTOM_RIGHT
        }

        // If not on a handle, it's a move
        return ResizeDirection.MOVE
    }

    private fun handleResize(rawDeltaX: Float, rawDeltaY: Float) {
        var newX = currentState.x
        var newY = currentState.y
        var newWidth = currentState.width
        var newHeight = currentState.height

        when (currentResizeDirection) {
            ResizeDirection.TOP_LEFT -> {
                // Anchor point is bottom-right
                newWidth = (initialMaskWidth - rawDeltaX).toInt()
                newX = (initialMaskX + rawDeltaX).toInt()
                newHeight = (initialMaskHeight - rawDeltaY).toInt()
                newY = (initialMaskY + rawDeltaY).toInt()
            }
            ResizeDirection.BOTTOM_RIGHT -> {
                // Anchor point is top-left
                newWidth = (initialMaskWidth + rawDeltaX).toInt()
                newHeight = (initialMaskHeight + rawDeltaY).toInt()
            }
            else -> return // Only handle corner resizes
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

    private fun updateMaskStampSize() {
        val parentWidth = this.width
        val parentHeight = this.height

        // Size should be 0.5f of the mask size (50%)
        val stampSize = minOf(parentWidth, parentHeight) * 0.5f

        maskStampImageView.layoutParams = (maskStampImageView.layoutParams as LayoutParams).apply {
            width = stampSize.toInt()
            height = stampSize.toInt()
            gravity = Gravity.CENTER
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (currentState.isBillboardVisible && currentState.billboardImageUri != null) {
            applyImagePadding()
        }
        updateMaskStampSize()
    }

    fun setHighlighted(highlighted: Boolean) {
        if (highlighted) {
            foreground = GradientDrawable().apply {
                setStroke(dpToPx(3), Color.parseColor("#FFFACD"))  // Yellow border as specified
                setColor(Color.TRANSPARENT)
            }
        } else {
            foreground = null
        }
        invalidate()
    }

    fun showMessage(message: String) {
        Snackbar.make(this, message, Snackbar.LENGTH_LONG)
            .setAnchorView(settingsButton) // Position above settings button
            .show()
    }

    // Update the lock button display based on lock state
    fun updateLockButtonState() {
        val lockDrawable = if (currentState.isLocked) {
            R.drawable.ic_lock_open
        } else {
            R.drawable.ic_lock
        }
        lockButton.setImageDrawable(ContextCompat.getDrawable(context, lockDrawable))

        val lockAllDrawable = if (isLockedByLockAll) {
            R.drawable.ic_lock_all_open
        } else {
            R.drawable.ic_lock_all
        }
        lockAllButton.setImageDrawable(ContextCompat.getDrawable(context, lockAllDrawable))
    }
}