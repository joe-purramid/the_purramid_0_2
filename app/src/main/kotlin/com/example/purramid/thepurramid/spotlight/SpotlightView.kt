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
import com.example.purramid.thepurramid.spotlight.SpotlightOpening
import com.example.purramid.thepurramid.util.dpToPx
import kotlin.math.absoluteValue
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.maxOf

/**
 * Custom view that renders a semi-opaque overlay with multiple spotlight openings (holes).
 * This is a single view that manages all openings for one service instance.
 */
class SpotlightView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // --- Listener Interface ---
    interface SpotlightInteractionListener {
        fun onOpeningMoved(openingId: Int, newX: Float, newY: Float)
        fun onOpeningResized(opening: SpotlightOpening)
        fun onOpeningShapeToggled(openingId: Int)
        fun onOpeningLockToggled(openingId: Int)
        fun onAllLocksToggled()
        fun onOpeningDeleted(openingId: Int)
        fun onAddNewOpeningRequested()
        fun onControlsToggled()
        fun onSettingsRequested()
    }

    var interactionListener: SpotlightInteractionListener? = null

    // --- Colors per Specification ---
    private val maskColor = Color.parseColor("#36454F") // Charcoal
    private val maskPaint = Paint().apply {
        color = maskColor
        alpha = 128 // 0.5 opacity = 128/255
    }

    private val spotlightPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }

    private val lockBorderPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = context.dpToPx(3).toFloat()
        isAntiAlias = true
    }

    // --- State Management ---
    private var currentState: SpotlightUiState = SpotlightUiState()
    private var openings: List<SpotlightOpening> = emptyList()
    private var showControls = true
    private var showSettingsMenu = false
    private var selectedOpeningId: Int? = null
    private var individuallyLockedOpenings = mutableSetOf<Int>() // Tracks which openings were individually locked
    private var isLockAllActive = false // Tracks if Lock All is active
    private var settingsMenuAnimator: ValueAnimator? = null
    private var settingsMenuAnimationProgress = 0f
    private var isSettingsMenuAnimating = false

    // --- Touch Handling ---
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapTimeout = ViewConfiguration.getTapTimeout().toLong()
    private var isDraggingOpening = false
    private var isResizingOpening = false
    private var downX = 0f
    private var downY = 0f
    private var downTime: Long = 0
    private var activePointerId = MotionEvent.INVALID_POINTER_ID  // Keep this if used
    private var lastInteractedTime = mutableMapOf<Int, Long>()

    // Interaction states
    private var activeOpening: SpotlightOpening? = null
    private var currentDraggingSpotlight: SpotlightOpening? = null
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var openingStartX = 0f
    private var openingStartY = 0f
    private var dragInitialSpotlightX = 0f
    private var dragInitialSpotlightY = 0f

    // State for resizing opening
    private var resizingOpening: SpotlightOpening? = null
    private var initialDistance = 0f
    private var initialOpeningRadius = 0f
    private var initialOpeningWidth = 0f
    private var initialOpeningHeight = 0f
    private var resizeAnchorX = 0f
    private var resizeAnchorY = 0f

    // Pointer tracking for pinch gestures
    private var pointerId1 = MotionEvent.INVALID_POINTER_ID
    private var pointerId2 = MotionEvent.INVALID_POINTER_ID

    // Visual feedback
    private var visualFeedbackOpening: SpotlightOpening? = null

    // Control button definitions
    private val controlButtonSize = context.dpToPx(48)
    private val controlMargin = context.dpToPx(16)
    private val controlIconSize = context.dpToPx(24)

    // Drawables for controls
    private var moveDrawable: Drawable? = null
    private var resizeDrawable: Drawable? = null
    private var settingsDrawable: Drawable? = null
    private var closeDrawable: Drawable? = null
    private var shapeDrawable: Drawable? = null
    private var lockDrawable: Drawable? = null
    private var lockOpenDrawable: Drawable? = null
    private var lockAllDrawable: Drawable? = null
    private var lockAllOpenDrawable: Drawable? = null
    private var addDrawable: Drawable? = null

    // For drawing
    private val ovalRect = RectF()
    private val path = Path()

    // Minimum size for openings
    private val minDimensionPx = context.dpToPx(50).toFloat()

    init {
        loadDrawables()
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun loadDrawables() {
        moveDrawable = ContextCompat.getDrawable(context, R.drawable.ic_move)?.mutate()
        resizeDrawable = ContextCompat.getDrawable(context, R.drawable.ic_resize_right_handle)?.mutate()
        settingsDrawable = ContextCompat.getDrawable(context, R.drawable.ic_settings)?.mutate()
        closeDrawable = ContextCompat.getDrawable(context, R.drawable.ic_close)?.mutate()
        shapeDrawable = ContextCompat.getDrawable(context, R.drawable.ic_spotlight_shape)?.mutate()
        lockDrawable = ContextCompat.getDrawable(context, R.drawable.ic_lock)?.mutate()
        lockOpenDrawable = ContextCompat.getDrawable(context, R.drawable.ic_lock_open)?.mutate()
        lockAllDrawable = ContextCompat.getDrawable(context, R.drawable.ic_lock_all)?.mutate()
        lockAllOpenDrawable = ContextCompat.getDrawable(context, R.drawable.ic_lock_all_open)?.mutate()
        addDrawable = ContextCompat.getDrawable(context, R.drawable.ic_add_circle)?.mutate()
    }

    private fun initializeAnimations() {
        settingsMenuAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300 // 300ms per universal requirements
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                settingsMenuAnimationProgress = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    isSettingsMenuAnimating = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    isSettingsMenuAnimating = false
                    // If closing, actually hide the menu
                    if (settingsMenuAnimationProgress == 0f) {
                        showSettingsMenu = false
                        selectedOpeningId = null
                    }
                }
            })
        }
    }

    fun updateState(state: SpotlightUiState) {
        currentState = state
        openings = state.openings
        showControls = state.showControls
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw semi-opaque overlay
        canvas.drawColor(maskColor)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

        // Create path for all openings
        path.reset()

        // Sort openings so most recently interacted are drawn last (on top)
        val sortedForDrawing = openings.sortedBy { opening ->
            lastInteractedTime[opening.openingId] ?: opening.displayOrder.toLong()
        }

        // Use visual feedback opening if actively interacting
        val drawList = if (visualFeedbackOpening != null) {
            sortedForDrawing.map { opening ->
                if (opening.openingId == visualFeedbackOpening?.openingId) {
                    visualFeedbackOpening!!
                } else {
                    opening
                }
            }
        } else {
            sortedForDrawing
        }

        // Add all openings to path
        drawList.forEach { opening ->
            when (opening.shape) {
                SpotlightOpening.Shape.CIRCLE -> {
                    path.addCircle(opening.centerX, opening.centerY, opening.radius, Path.Direction.CW)
                }
                SpotlightOpening.Shape.OVAL -> {
                    ovalRect.set(
                        opening.centerX - opening.width / 2f,
                        opening.centerY - opening.height / 2f,
                        opening.centerX + opening.width / 2f,
                        opening.centerY + opening.height / 2f
                    )
                    path.addOval(ovalRect, Path.Direction.CW)
                }
                SpotlightOpening.Shape.SQUARE -> {
                    val halfSize = opening.size / 2f
                    path.addRect(
                        opening.centerX - halfSize,
                        opening.centerY - halfSize,
                        opening.centerX + halfSize,
                        opening.centerY + halfSize,
                        Path.Direction.CW
                    )
                }
                SpotlightOpening.Shape.RECTANGLE -> {
                    path.addRect(
                        opening.centerX - opening.width / 2f,
                        opening.centerY - opening.height / 2f,
                        opening.centerX + opening.width / 2f,
                        opening.centerY + opening.height / 2f,
                        Path.Direction.CW
                    )
                }
            }

            // Draw lock border if locked
            if (opening.isLocked) {
                drawLockBorder(canvas, opening)
            }
        }

        // Clear all openings at once
        canvas.drawPath(path, spotlightPaint)

        // Draw controls for each opening if enabled
        if (showControls) {
            openings.forEach { opening ->
                drawControlsForOpening(canvas, opening)
            }
        }
    }

    private fun drawLockBorder(canvas: Canvas, opening: SpotlightOpening) {
        when (opening.shape) {
            SpotlightOpening.Shape.CIRCLE -> {
                canvas.drawCircle(
                    opening.centerX,
                    opening.centerY,
                    opening.radius + lockBorderPaint.strokeWidth / 2,
                    lockBorderPaint
                )
            }
            SpotlightOpening.Shape.OVAL -> {
                ovalRect.set(
                    opening.centerX - opening.width / 2f - lockBorderPaint.strokeWidth / 2,
                    opening.centerY - opening.height / 2f - lockBorderPaint.strokeWidth / 2,
                    opening.centerX + opening.width / 2f + lockBorderPaint.strokeWidth / 2,
                    opening.centerY + opening.height / 2f + lockBorderPaint.strokeWidth / 2
                )
                canvas.drawOval(ovalRect, lockBorderPaint)
            }
            SpotlightOpening.Shape.SQUARE -> {
                val halfSize = opening.size / 2f + lockBorderPaint.strokeWidth / 2
                canvas.drawRect(
                    opening.centerX - halfSize,
                    opening.centerY - halfSize,
                    opening.centerX + halfSize,
                    opening.centerY + halfSize,
                    lockBorderPaint
                )
            }
            SpotlightOpening.Shape.RECTANGLE -> {
                canvas.drawRect(
                    opening.centerX - opening.width / 2f - lockBorderPaint.strokeWidth / 2,
                    opening.centerY - opening.height / 2f - lockBorderPaint.strokeWidth / 2,
                    opening.centerX + opening.width / 2f + lockBorderPaint.strokeWidth / 2,
                    opening.centerY + opening.height / 2f + lockBorderPaint.strokeWidth / 2,
                    lockBorderPaint
                )
            }
        }
    }

    private fun drawControlsForOpening(canvas: Canvas, opening: SpotlightOpening) {
        val bounds = getOpeningBounds(opening)

        // Position controls per specification
        // Move handle - top left
        val moveX = bounds.left - controlButtonSize / 2
        val moveY = bounds.top - controlButtonSize / 2
        drawControl(canvas, moveDrawable, moveX, moveY, opening.isLocked)

        // Close button - top right
        val closeX = bounds.right - controlButtonSize / 2
        val closeY = bounds.top - controlButtonSize / 2
        drawControl(canvas, closeDrawable, closeX, closeY, opening.isLocked)

        // Settings button - bottom left
        val settingsX = bounds.left - controlButtonSize / 2
        val settingsY = bounds.bottom - controlButtonSize / 2
        drawControl(canvas, settingsDrawable, settingsX, settingsY, false)

        // Resize handle - bottom right
        val resizeX = bounds.right - controlButtonSize / 2
        val resizeY = bounds.bottom - controlButtonSize / 2
        if (opening.isLocked) {
            // Draw inactive resize handle when locked
            val inactiveResize = ContextCompat.getDrawable(context, R.drawable.ic_resize_right_handle)?.mutate()
            inactiveResize?.alpha = 128
            drawControl(canvas, inactiveResize, resizeX, resizeY, true)
        } else {
            drawControl(canvas, resizeDrawable, resizeX, resizeY, false)
        }

        // Only draw settings menu if this is the selected opening
        if (selectedOpeningId == opening.openingId && showSettingsMenu) {
            drawSettingsMenu(canvas, settingsX, settingsY, opening)
        }
    }

    private fun drawControl(canvas: Canvas, drawable: Drawable?, centerX: Float, centerY: Float, disabled: Boolean = false, isActive: Boolean = false, alpha: Float = 1f) {
        drawable?.let {
            val halfSize = controlIconSize / 2
            it.setBounds(
                (centerX - halfSize).toInt(),
                (centerY - halfSize).toInt(),
                (centerX + halfSize).toInt(),
                (centerY + halfSize).toInt()
            )
            val finalAlpha = when {
                disabled -> (128 * alpha).toInt()
                else -> (255 * alpha).toInt()
            }

            it.alpha = finalAlpha

            if (isActive && !disabled) {
                it.setTint(Color.parseColor("#808080"))
            } else {
                it.setTintList(null)
            }

            it.draw(canvas)
        }
    }

    private fun drawSettingsMenu(canvas: Canvas, anchorX: Float, anchorY: Float, opening: SpotlightOpening) {
        if (settingsMenuAnimationProgress == 0f) return

        // Settings menu extends upward from the settings button
        val menuWidth = controlButtonSize
        val menuItemHeight = controlButtonSize
        val menuItemCount = 4 // Shape, Lock, Lock All, Add Another
        val fullMenuHeight = menuItemHeight * menuItemCount

        // Animate menu height
        val menuHeight = fullMenuHeight * settingsMenuAnimationProgress

        // Background for menu (semi-transparent)
        val menuPaint = Paint().apply {
            color = Color.parseColor("#36454F")
            alpha = (200 * settingsMenuAnimationProgress).toInt() // Fade in/out
        }

        val menuLeft = anchorX - menuWidth / 2
        val menuTop = anchorY - menuHeight - controlButtonSize / 2
        val menuRight = menuLeft + menuWidth
        val menuBottom = anchorY - controlButtonSize / 2

        // Draw menu background
        canvas.drawRect(menuLeft, menuTop, menuRight, menuBottom, menuPaint)

        // Only draw items that fit in current menu height
        var currentY = anchorY - controlButtonSize / 2 - menuItemHeight / 2

        // Draw items from bottom to top (so they appear to slide up)
        val itemsToShow = (menuHeight / menuItemHeight).toInt()

        if (itemsToShow >= 4) {
            // Shape button (top item, shows last)
            val shapeY = currentY - menuItemHeight * 3
            val shapeAlpha = ((settingsMenuAnimationProgress - 0.75f) * 4).coerceIn(0f, 1f)
            drawControl(canvas, shapeDrawable, anchorX, shapeY, opening.isLocked, alpha = shapeAlpha)
        }

        if (itemsToShow >= 3) {
            // Lock button
            val lockY = currentY - menuItemHeight * 2
            val lockAlpha = ((settingsMenuAnimationProgress - 0.5f) * 2).coerceIn(0f, 1f)
            val lockIcon = if (opening.isLocked) lockDrawable else lockOpenDrawable
            drawControl(canvas, lockIcon, anchorX, lockY, false, alpha = lockAlpha)
        }

        if (itemsToShow >= 2) {
            // Lock All button
            val lockAllY = currentY - menuItemHeight
            val lockAllAlpha = ((settingsMenuAnimationProgress - 0.25f) * 1.33f).coerceIn(0f, 1f)
            val lockAllIcon = if (isLockAllActive) lockAllDrawable else lockAllOpenDrawable
            drawControl(canvas, lockAllIcon, anchorX, lockAllY, false, alpha = lockAllAlpha)
        }

        if (itemsToShow >= 1) {
            // Add Another button (bottom item, shows first)
            val addAlpha = settingsMenuAnimationProgress
            drawControl(canvas, addDrawable, anchorX, currentY, !currentState.canAddMore, alpha = addAlpha)
        }
    }

    private fun getOpeningBounds(opening: SpotlightOpening): RectF {
        return when (opening.shape) {
            SpotlightOpening.Shape.CIRCLE -> {
                RectF(
                    opening.centerX - opening.radius,
                    opening.centerY - opening.radius,
                    opening.centerX + opening.radius,
                    opening.centerY + opening.radius
                )
            }
            SpotlightOpening.Shape.OVAL -> {
                RectF(
                    opening.centerX - opening.width / 2f,
                    opening.centerY - opening.height / 2f,
                    opening.centerX + opening.width / 2f,
                    opening.centerY + opening.height / 2f
                )
            }
            SpotlightOpening.Shape.SQUARE -> {
                val halfSize = opening.size / 2f
                RectF(
                    opening.centerX - halfSize,
                    opening.centerY - halfSize,
                    opening.centerX + halfSize,
                    opening.centerY + halfSize
                )
            }
            SpotlightOpening.Shape.RECTANGLE -> {
                RectF(
                    opening.centerX - opening.width / 2f,
                    opening.centerY - opening.height / 2f,
                    opening.centerX + opening.width / 2f,
                    opening.centerY + opening.height / 2f
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                pointerId1 = event.getPointerId(0)

                // Check what was touched
                val touchedOpening = findOpeningAt(downX, downY)
                val touchedControl = if (showControls) findControlAt(downX, downY) else null

                if (touchedControl != null) {
                    handleControlTouch(touchedControl)
                    return true
                }

                if (touchedOpening != null) {
                    activeOpening = touchedOpening
                    dragStartX = downX
                    dragStartY = downY
                    openingStartX = touchedOpening.centerX
                    openingStartY = touchedOpening.centerY

                    // Track this as the most recently interacted opening
                    lastInteractedTime[touchedOpening.openingId] = System.currentTimeMillis()

                    return true
                }

                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger down - check for resize
                if (event.pointerCount == 2 && !isDraggingOpening && !isResizingOpening) {
                    pointerId2 = event.getPointerId(event.actionIndex)

                    val index1 = event.findPointerIndex(pointerId1)
                    val index2 = event.findPointerIndex(pointerId2)

                    if (index1 != -1 && index2 != -1) {
                        val x1 = event.getX(index1)
                        val y1 = event.getY(index1)
                        val x2 = event.getX(index2)
                        val y2 = event.getY(index2)

                        // Check if we're touching a resize handle
                        val resizeControl = findControlAt(x1, y1)
                        if (resizeControl?.type == ControlType.RESIZE && resizeControl.opening.isLocked == false) {
                            // Start resize with resize handle as anchor
                            startResize(resizeControl.opening, x1, y1, x2, y2, useHandleAsAnchor = true)
                        } else {
                            // Check if both fingers are on the same opening
                            val midX = (x1 + x2) / 2f
                            val midY = (y1 + y2) / 2f
                            val touchedOpening = findOpeningAt(midX, midY)

                            if (touchedOpening != null && !touchedOpening.isLocked) {
                                // Start resize with opening center as anchor
                                startResize(touchedOpening, x1, y1, x2, y2, useHandleAsAnchor = false)
                            }
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Handle resize
                if (isResizingOpening && resizingOpening != null && event.pointerCount >= 2) {
                    val index1 = event.findPointerIndex(pointerId1)
                    val index2 = event.findPointerIndex(pointerId2)

                    if (index1 != -1 && index2 != -1) {
                        val x1 = event.getX(index1)
                        val y1 = event.getY(index1)
                        val x2 = event.getX(index2)
                        val y2 = event.getY(index2)

                        val currentDistance = hypot(x2 - x1, y2 - y1)
                        if (initialDistance > 0) {
                            val scale = currentDistance / initialDistance

                            // Update visual feedback
                            visualFeedbackOpening?.let { feedback ->
                                applyResize(feedback, scale)
                            }
                            invalidate()
                        }
                    }
                    return true
                }

                // Handle drag (existing code)
                activeOpening?.let { opening ->
                    if (!opening.isLocked && !isResizingOpening) {
                        val deltaX = event.x - dragStartX
                        val deltaY = event.y - dragStartY

                        if (!isDraggingOpening && hypot(deltaX, deltaY) > touchSlop) {
                            isDraggingOpening = true
                            visualFeedbackOpening = opening.copy()
                        }

                        if (isDraggingOpening) {
                            visualFeedbackOpening?.let {
                                it.centerX = openingStartX + deltaX
                                it.centerY = openingStartY + deltaY

                                // Constrain to screen bounds
                                val bounds = getOpeningBounds(it)
                                if (bounds.left < 0) it.centerX -= bounds.left
                                if (bounds.top < 0) it.centerY -= bounds.top
                                if (bounds.right > width) it.centerX -= (bounds.right - width)
                                if (bounds.bottom > height) it.centerY -= (bounds.bottom - height)
                            }
                            invalidate()
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upPointerId = event.getPointerId(event.actionIndex)
                if (isResizingOpening && (upPointerId == pointerId1 || upPointerId == pointerId2)) {
                    // Finish resize
                    visualFeedbackOpening?.let {
                        interactionListener?.onOpeningResized(it)
                    }
                    endResize()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val upTime = System.currentTimeMillis()
                val deltaTime = upTime - downTime
                val deltaX = (event.x - downX).absoluteValue
                val deltaY = (event.y - downY).absoluteValue

                // Handle drag completion
                if (isDraggingOpening) {
                    visualFeedbackOpening?.let {
                        interactionListener?.onOpeningMoved(it.openingId, it.centerX, it.centerY)
                    }
                    isDraggingOpening = false
                    visualFeedbackOpening = null
                    activeOpening = null
                    invalidate()
                    return true
                }

                // Handle tap
                if (deltaTime < tapTimeout && deltaX < touchSlop && deltaY < touchSlop) {
                    val tappedOpening = findOpeningAt(downX, downY)

                    if (showSettingsMenu && !isSettingsMenuAnimating) {
                        // Settings menu is open - check if tap is outside menu area
                        val menuBounds = getSettingsMenuBounds(selectedOpeningId)
                        if (menuBounds == null || !menuBounds.contains(downX, downY)) {
                            closeSettingsMenu()
                        }
                    } else if (tappedOpening == null && !showControls) {
                        // Tapped on mask area - toggle controls
                        interactionListener?.onControlsToggled()
                    }
                }

                activeOpening = null
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDraggingOpening = false
                isResizingOpening = false
                visualFeedbackOpening = null
                activeOpening = null
                resizingOpening = null
                pointerId1 = MotionEvent.INVALID_POINTER_ID
                pointerId2 = MotionEvent.INVALID_POINTER_ID
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun findOpeningAt(x: Float, y: Float): SpotlightOpening? {
        // Sort by interaction time (most recent first) or display order
        val sortedOpenings = openings.sortedByDescending { opening ->
            lastInteractedTime[opening.openingId] ?: opening.displayOrder.toLong()
        }

        return sortedOpenings.firstOrNull { opening ->
            isPointInOpening(x, y, opening)
        }
    }

    private fun isPointInOpening(x: Float, y: Float, opening: SpotlightOpening): Boolean {
        return when (opening.shape) {
            SpotlightOpening.Shape.CIRCLE -> {
                hypot(x - opening.centerX, y - opening.centerY) <= opening.radius
            }
            SpotlightOpening.Shape.OVAL -> {
                val a = opening.width / 2f
                val b = opening.height / 2f
                if (a > 0 && b > 0) {
                    val dx = (x - opening.centerX) / a
                    val dy = (y - opening.centerY) / b
                    (dx * dx + dy * dy) <= 1
                } else false
            }
            SpotlightOpening.Shape.SQUARE -> {
                val halfSize = opening.size / 2f
                x >= opening.centerX - halfSize && x <= opening.centerX + halfSize &&
                        y >= opening.centerY - halfSize && y <= opening.centerY + halfSize
            }
            SpotlightOpening.Shape.RECTANGLE -> {
                val halfWidth = opening.width / 2f
                val halfHeight = opening.height / 2f
                x >= opening.centerX - halfWidth && x <= opening.centerX + halfWidth &&
                        y >= opening.centerY - halfHeight && y <= opening.centerY + halfHeight
            }
        }
    }

    private data class ControlInfo(
        val opening: SpotlightOpening,
        val type: ControlType,
        val bounds: RectF
    )

    private enum class ControlType {
        MOVE, RESIZE, SETTINGS, CLOSE, SHAPE, LOCK, LOCK_ALL, ADD
    }

    private fun findControlAt(x: Float, y: Float): ControlInfo? {
        val touchRadius = controlButtonSize / 2f

        // First check if settings menu is open
        if (showSettingsMenu && selectedOpeningId != null) {
            val selectedOpening = openings.find { it.openingId == selectedOpeningId }
            selectedOpening?.let { opening ->
                val bounds = getOpeningBounds(opening)
                val settingsX = bounds.left - controlButtonSize / 2
                val settingsY = bounds.bottom - controlButtonSize / 2

                // Check settings menu items
                val menuLeft = settingsX - touchRadius
                val menuRight = settingsX + touchRadius
                var menuY = settingsY - controlButtonSize * 4.5f // Start from top of menu

                // Shape button
                if (x >= menuLeft && x <= menuRight && y >= menuY && y <= menuY + controlButtonSize) {
                    return ControlInfo(opening, ControlType.SHAPE, RectF(menuLeft, menuY, menuRight, menuY + controlButtonSize))
                }
                menuY += controlButtonSize

                // Lock button
                if (x >= menuLeft && x <= menuRight && y >= menuY && y <= menuY + controlButtonSize) {
                    return ControlInfo(opening, ControlType.LOCK, RectF(menuLeft, menuY, menuRight, menuY + controlButtonSize))
                }
                menuY += controlButtonSize

                // Lock All button
                if (x >= menuLeft && x <= menuRight && y >= menuY && y <= menuY + controlButtonSize) {
                    return ControlInfo(opening, ControlType.LOCK_ALL, RectF(menuLeft, menuY, menuRight, menuY + controlButtonSize))
                }
                menuY += controlButtonSize

                // Add Another button
                if (x >= menuLeft && x <= menuRight && y >= menuY && y <= menuY + controlButtonSize) {
                    return ControlInfo(opening, ControlType.ADD, RectF(menuLeft, menuY, menuRight, menuY + controlButtonSize))
                }
            }
        }

        // Then check regular controls
        openings.forEach { opening ->
            val bounds = getOpeningBounds(opening)

            // Check each control position
            val controls = listOf(
                // Move - top left
                ControlInfo(
                    opening,
                    ControlType.MOVE,
                    RectF(
                        bounds.left - touchRadius,
                        bounds.top - touchRadius,
                        bounds.left + touchRadius,
                        bounds.top + touchRadius
                    )
                ),
                // Close - top right
                ControlInfo(
                    opening,
                    ControlType.CLOSE,
                    RectF(
                        bounds.right - touchRadius,
                        bounds.top - touchRadius,
                        bounds.right + touchRadius,
                        bounds.top + touchRadius
                    )
                ),
                // Settings - bottom left
                ControlInfo(
                    opening,
                    ControlType.SETTINGS,
                    RectF(
                        bounds.left - touchRadius,
                        bounds.bottom - touchRadius,
                        bounds.left + touchRadius,
                        bounds.bottom + touchRadius
                    )
                ),
                // Resize - bottom right
                ControlInfo(
                    opening,
                    ControlType.RESIZE,
                    RectF(
                        bounds.right - touchRadius,
                        bounds.bottom - touchRadius,
                        bounds.right + touchRadius,
                        bounds.bottom + touchRadius
                    )
                )
            )

            controls.forEach { control ->
                if (control.bounds.contains(x, y)) {
                    return control
                }
            }
        }

        return null
    }

    private fun startResize(
        opening: SpotlightOpening,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        useHandleAsAnchor: Boolean
    ) {
        isResizingOpening = true
        resizingOpening = opening
        visualFeedbackOpening = opening.copy()
        initialDistance = hypot(x2 - x1, y2 - y1)

        // Store initial dimensions
        initialOpeningRadius = opening.radius
        initialOpeningWidth = opening.width
        initialOpeningHeight = opening.height

        // Set anchor point
        if (useHandleAsAnchor) {
            // Use the move handle (top-left) as anchor when resizing from resize handle
            val bounds = getOpeningBounds(opening)
            resizeAnchorX = bounds.left
            resizeAnchorY = bounds.top
        } else {
            // Use center as anchor for pinch gesture
            resizeAnchorX = opening.centerX
            resizeAnchorY = opening.centerY
        }

        activeOpening = null // Cancel any drag
        isDraggingOpening = false
    }

    private fun applyResize(opening: SpotlightOpening, scale: Float) {
        val minSize = minDimensionPx
        val maxSize = minOf(width, height).toFloat()

        when (opening.shape) {
            SpotlightOpening.Shape.CIRCLE -> {
                opening.radius = (initialOpeningRadius * scale).coerceIn(minSize / 2f, maxSize / 2f)
                opening.width = opening.radius * 2
                opening.height = opening.radius * 2
                opening.size = opening.radius * 2
            }
            SpotlightOpening.Shape.SQUARE -> {
                val newSize = (opening.size * scale).coerceIn(minSize, maxSize)
                opening.size = newSize
                opening.width = newSize
                opening.height = newSize
                opening.radius = newSize / 2f
            }
            SpotlightOpening.Shape.OVAL, SpotlightOpening.Shape.RECTANGLE -> {
                opening.width = (initialOpeningWidth * scale).coerceIn(minSize, maxSize)
                opening.height = (initialOpeningHeight * scale).coerceIn(minSize, maxSize)
                opening.radius = maxOf(opening.width, opening.height) / 2f
                opening.size = maxOf(opening.width, opening.height)
            }
        }

        // Adjust position if using handle as anchor
        if (resizeAnchorX != opening.centerX || resizeAnchorY != opening.centerY) {
            // Keep the anchor point fixed
            val bounds = getOpeningBounds(opening)
            val deltaX = resizeAnchorX - bounds.left
            val deltaY = resizeAnchorY - bounds.top
            opening.centerX = resizeAnchorX + deltaX
            opening.centerY = resizeAnchorY + deltaY
        }
    }

    private fun endResize() {
        isResizingOpening = false
        resizingOpening = null
        pointerId1 = MotionEvent.INVALID_POINTER_ID
        pointerId2 = MotionEvent.INVALID_POINTER_ID
    }

    private fun getSettingsMenuBounds(openingId: Int?): RectF? {
        if (openingId == null) return null

        val opening = openings.find { it.openingId == openingId } ?: return null
        val bounds = getOpeningBounds(opening)

        // Calculate settings button position (bottom left)
        val settingsX = bounds.left - controlButtonSize / 2
        val settingsY = bounds.bottom - controlButtonSize / 2

        // Calculate menu bounds when fully open
        val menuWidth = controlButtonSize.toFloat()
        val menuHeight = controlButtonSize * 4f // 4 menu items

        return RectF(
            settingsX - menuWidth / 2,
            settingsY - menuHeight - controlButtonSize / 2,
            settingsX + menuWidth / 2,
            settingsY + controlButtonSize / 2  // Include settings button itself
        )
    }

    private fun handleControlTouch(control: ControlInfo) {
        // Track interaction time
        lastInteractedTime[control.opening.openingId] = System.currentTimeMillis()

        when (control.type) {
            ControlType.MOVE -> {
                if (!control.opening.isLocked) {
                    activeOpening = control.opening
                    isDraggingOpening = true
                    visualFeedbackOpening = control.opening.copy()
                }
            }
            ControlType.RESIZE -> {
                if (!control.opening.isLocked) {
                    // Resize will be handled by ACTION_POINTER_DOWN
                    // Just store that we're touching the resize handle
                    activeOpening = control.opening
                }
            }
            ControlType.SETTINGS -> {
                // Toggle settings menu for this opening
                if (selectedOpeningId == control.opening.openingId && showSettingsMenu && !isSettingsMenuAnimating) {
                    // Close menu if clicking on same settings button
                    settingsMenuAnimator?.reverse()
                } else {
                    // Open menu for this opening
                    showSettingsMenu = true
                    selectedOpeningId = control.opening.openingId
                    settingsMenuAnimator?.start()
                }
                invalidate()
            }
            ControlType.CLOSE -> {
                if (!control.opening.isLocked) {
                    interactionListener?.onOpeningDeleted(control.opening.openingId)
                }
            }
            ControlType.SHAPE -> {
                if (!control.opening.isLocked) {
                    interactionListener?.onOpeningShapeToggled(control.opening.openingId)
                    // Close settings menu after action
                    closeSettingsMenu()
                }
            }
            ControlType.LOCK -> {
                // Track if this was individually locked before Lock All
                val openingId = control.opening.openingId
                if (!control.opening.isLocked) {
                    individuallyLockedOpenings.add(openingId)
                } else {
                    individuallyLockedOpenings.remove(openingId)
                }

                interactionListener?.onOpeningLockToggled(openingId)
                // Close settings menu after action
                closeSettingsMenu()
            }
            ControlType.LOCK_ALL -> {
                if (!isLockAllActive) {
                    // Activating Lock All
                    isLockAllActive = true
                    // Remember which openings were already locked individually
                    openings.forEach { opening ->
                        if (opening.isLocked) {
                            individuallyLockedOpenings.add(opening.openingId)
                        }
                    }
                } else {
                    // Deactivating Lock All
                    isLockAllActive = false
                    // Only unlock openings that weren't individually locked
                    // This will be handled by the ViewModel
                }

                interactionListener?.onAllLocksToggled()
                // Close settings menu after action
                closeSettingsMenu()
            }
            ControlType.ADD -> {
                if (currentState.canAddMore) {
                    interactionListener?.onAddNewOpeningRequested()
                    // Close settings menu after action
                    closeSettingsMenu()
                }
            }
        }
    }

    // Update other control touches to close menu with animation
    private fun closeSettingsMenu() {
        if (showSettingsMenu && !isSettingsMenuAnimating) {
            settingsMenuAnimator?.reverse()
        }
    }


    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}