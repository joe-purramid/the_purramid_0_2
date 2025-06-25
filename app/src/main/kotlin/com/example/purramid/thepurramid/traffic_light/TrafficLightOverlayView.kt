// TrafficLightOverlayView.kt
package com.example.purramid.thepurramid.traffic_light

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout // If your root is ConstraintLayout
import androidx.core.view.isVisible
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.TrafficLightOverlayViewBinding
import com.example.purramid.thepurramid.traffic_light.viewmodel.LightColor
import com.example.purramid.thepurramid.traffic_light.viewmodel.Orientation
import com.example.purramid.thepurramid.traffic_light.viewmodel.TrafficLightState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class TrafficLightOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val instanceId: Int // Added instanceId to constructor
) : FrameLayout(context, attrs, defStyleAttr) { // Or ConstraintLayout if your XML root is that

    private val binding: TrafficLightOverlayViewBinding
    var interactionListener: InteractionListener? = null

    // Touch Handling Variables
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var initialViewX: Int = 0 // To store initial WindowManager.LayoutParams.x
    private var initialViewY: Int = 0 // To store initial WindowManager.LayoutParams.y
    private var isMoving = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val viewBoundsRect = Rect() // For checking child view bounds

    // Resize/Scale Handling Variables
    private var scaleGestureDetector: ScaleGestureDetector
    private var initialViewWidth: Int = 0
    private var initialViewHeight: Int = 0
    private var scaleFactor = 1f
    private var isResizingWithScale = false // Flag for pinch-to-zoom resizing
    private val minSizePx = resources.getDimensionPixelSize(R.dimen.traffic_light_min_size)
    // No explicit maxSizePx here, WindowManager will clip if it exceeds screen

    // Blinking Variables
    private var blinkingAnimator: ObjectAnimator? = null
    private var currentlyBlinkingView: View? = null
    private val blinkDuration = 750L

    interface InteractionListener {
        fun onLightTapped(instanceId: Int, color: LightColor)
        fun onCloseRequested(instanceId: Int)
        fun onSettingsRequested(instanceId: Int)
        fun onMove(instanceId: Int, rawDeltaX: Float, rawDeltaY: Float) // Delta for WM
        fun onMoveFinished(instanceId: Int) // To save final position
        fun onResize(instanceId: Int, newWidth: Int, newHeight: Int) // For WM
        fun onResizeFinished(instanceId: Int, finalWidth: Int, finalHeight: Int) // To save final size
    }

    init {
        binding = TrafficLightOverlayViewBinding.inflate(LayoutInflater.from(context), this, true)
        setupInternalListeners()
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        setupOverlayTouchListener() // Setup touch listener for the overlay view itself
    }

    private fun setupInternalListeners() {
        // Vertical Lights
        binding.lightRedVerticalOverlay.setOnClickListener { interactionListener?.onLightTapped(instanceId, LightColor.RED) }
        binding.lightYellowVerticalOverlay.setOnClickListener { interactionListener?.onLightTapped(instanceId, LightColor.YELLOW) }
        binding.lightGreenVerticalOverlay.setOnClickListener { interactionListener?.onLightTapped(instanceId, LightColor.GREEN) }

        // Horizontal Lights
        binding.lightRedHorizontalOverlay.setOnClickListener { interactionListener?.onLightTapped(instanceId, LightColor.RED) }
        binding.lightYellowHorizontalOverlay.setOnClickListener { interactionListener?.onLightTapped(instanceId, LightColor.YELLOW) }
        binding.lightGreenHorizontalOverlay.setOnClickListener { interactionListener?.onLightTapped(instanceId, LightColor.GREEN) }

        // Buttons
        binding.overlayButtonClose.setOnClickListener { interactionListener?.onCloseRequested(instanceId) }
        binding.overlayButtonSettings.setOnClickListener { interactionListener?.onSettingsRequested(instanceId) }
    }

    fun updateState(state: TrafficLightState) {
        val isVertical = state.orientation == Orientation.VERTICAL
        binding.trafficLightVerticalContainerOverlay.isVisible = isVertical
        binding.trafficLightHorizontalContainerOverlay.isVisible = !isVertical

        val activeColor = state.activeLight
        val blinkEnabled = state.isBlinkingEnabled

        val redView = if (isVertical) binding.lightRedVerticalOverlay else binding.lightRedHorizontalOverlay
        val yellowView = if (isVertical) binding.lightYellowVerticalOverlay else binding.lightYellowHorizontalOverlay
        val greenView = if (isVertical) binding.lightGreenVerticalOverlay else binding.lightGreenHorizontalOverlay

        redView.isActivated = activeColor == LightColor.RED
        yellowView.isActivated = activeColor == LightColor.YELLOW
        greenView.isActivated = activeColor == LightColor.GREEN

        val viewToBlink: View? = when (activeColor) {
            LightColor.RED -> redView
            LightColor.YELLOW -> yellowView
            LightColor.GREEN -> greenView
            null -> null
        }

        // Stop previous blinking if conditions change
        if (currentlyBlinkingView != null && (currentlyBlinkingView != viewToBlink || !blinkEnabled || activeColor == null)) {
            stopBlinking(currentlyBlinkingView!!)
        }

        // Ensure non-active lights are not blinking and have full alpha
        if (activeColor != LightColor.RED) { stopBlinking(redView); redView.alpha = 1f }
        if (activeColor != LightColor.YELLOW) { stopBlinking(yellowView); yellowView.alpha = 1f }
        if (activeColor != LightColor.GREEN) { stopBlinking(greenView); greenView.alpha = 1f }


        if (blinkEnabled && viewToBlink != null) {
            if (currentlyBlinkingView != viewToBlink) { // Only start if not already blinking this view
                startBlinking(viewToBlink)
            }
        } else if (!blinkEnabled && viewToBlink != null) {
            // If blinking got disabled but a light is active, ensure its alpha is full
            stopBlinking(viewToBlink) // Stop just in case
            viewToBlink.alpha = 1f
        }
    }

    private fun startBlinking(view: View) {
        stopBlinking(view) // Stop any existing animation on this view first
        blinkingAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.3f, 1f).apply {
            duration = blinkDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            start()
        }
        currentlyBlinkingView = view
    }

    private fun stopBlinking(view: View) {
        if (currentlyBlinkingView == view && blinkingAnimator?.isRunning == true) {
            blinkingAnimator?.cancel()
        }
        // Always reset alpha if we are stopping blinking for this view
        view.alpha = 1f
        if (currentlyBlinkingView == view) {
            currentlyBlinkingView = null
            blinkingAnimator = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayTouchListener() {
        this.setOnTouchListener { _, event ->
            // Check if touch is on an interactive element first (buttons, lights)
            if (isTouchOnInteractiveElement(event) && event.actionMasked == MotionEvent.ACTION_DOWN) {
                // If ACTION_DOWN is on button/light, let it proceed but don't start move/scale for the whole overlay
                isMoving = false
                isResizingWithScale = false
                return@setOnTouchListener false // Let the child handle its click
            }

            val scaleConsumed = scaleGestureDetector.onTouchEvent(event)
            var moveConsumed = false

            if (!isResizingWithScale) { // Don't move if currently scaling
                moveConsumed = handleMoveGesture(event)
            }

            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (isMoving) {
                    interactionListener?.onMoveFinished(instanceId)
                    isMoving = false
                }
                if (isResizingWithScale) { // isResizingWithScale is set in ScaleListener
                    interactionListener?.onResizeFinished(instanceId, width, height)
                    isResizingWithScale = false
                }
            }
            // Consume if scale or move handled it, or if it's ACTION_DOWN to prepare for gestures
            scaleConsumed || moveConsumed || event.actionMasked == MotionEvent.ACTION_DOWN
        }
    }

    private fun handleMoveGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX // Screen coordinates
                initialTouchY = event.rawY
                // initialViewX and initialViewY will be set by the service when params are known
                // For now, we just pass deltas. The service knows the current WM params.x/y
                isMoving = false
                return true // Interested in subsequent move events
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) { // If multi-touch starts, stop considering it a move
                    isMoving = false
                    return false
                }
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY

                if (!isMoving) { // Check for slop if not already moving
                    if (sqrt(deltaX * deltaX + deltaY * deltaY) > touchSlop) {
                        isMoving = true
                    }
                }

                if (isMoving) {
                    interactionListener?.onMove(instanceId, deltaX, deltaY)
                    // Update initial touch points for the next delta calculation for continuous drag
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    return true
                }
            }
        }
        return false
    }

    // Helper to check if touch is within the bounds of any interactive child
    private fun isTouchOnInteractiveElement(event: MotionEvent): Boolean {
        val x = event.x.toInt() // Coordinates relative to this TrafficLightOverlayView
        val y = event.y.toInt()

        val interactiveViews = listOfNotNull(
            binding.overlayButtonClose, binding.overlayButtonSettings,
            binding.lightRedVerticalOverlay, binding.lightYellowVerticalOverlay, binding.lightGreenVerticalOverlay,
            binding.lightRedHorizontalOverlay, binding.lightYellowHorizontalOverlay, binding.lightGreenHorizontalOverlay
        )

        for (view in interactiveViews) {
            if (view.isVisible) {
                view.getHitRect(viewBoundsRect) // Gets rect relative to this parent view
                if (viewBoundsRect.contains(x, y)) {
                    return true
                }
            }
        }
        return false
    }


    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (isMoving) return false // Don't scale if already moving

            isResizingWithScale = true
            initialViewWidth = width // Current width from the view itself
            initialViewHeight = height // Current height from the view itself
            scaleFactor = 1.0f
            Log.d("TrafficLightOverlay", "onScaleBegin - Start Size: ${initialViewWidth}x$initialViewHeight")
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!isResizingWithScale) return false
            scaleFactor *= detector.scaleFactor // Accumulate scale factor

            var newWidth = (initialViewWidth * scaleFactor).toInt()
            var newHeight = (initialViewHeight * scaleFactor).toInt()

            newWidth = max(minSizePx, newWidth)
            newHeight = max(minSizePx, newHeight)
            // No explicit max size, window manager will handle screen bounds

            interactionListener?.onResize(instanceId, newWidth, newHeight)
            // The service will update WindowManager.LayoutParams, which will trigger onMeasure/onLayout
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (isResizingWithScale) {
                // Final size based on the accumulated scaleFactor
                var finalWidth = (initialViewWidth * scaleFactor).toInt()
                var finalHeight = (initialViewHeight * scaleFactor).toInt()
                finalWidth = max(minSizePx, finalWidth)
                finalHeight = max(minSizePx, finalHeight)

                interactionListener?.onResizeFinished(instanceId, finalWidth, finalHeight)
            }
            isResizingWithScale = false
            scaleFactor = 1.0f
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        blinkingAnimator?.cancel()
        blinkingAnimator = null
        currentlyBlinkingView = null
    }
}