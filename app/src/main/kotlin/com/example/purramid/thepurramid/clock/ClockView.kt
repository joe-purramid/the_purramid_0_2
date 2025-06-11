// ClockView.kt
package com.example.purramid.thepurramid.clock // Use your package name

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import com.caverock.androidsvg.SVGImageView
import com.example.purramid.thepurramid.util.dpToPx
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A custom view that displays a clock, either analog or digital.
 * This view does NOT manage its own time state. It relies on external updates
 * via `updateDisplayTime` and configuration via setter methods.
 * It reports user interactions (dragging hands) back via ClockInteractionListener.
 */
class ClockView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // --- Listener for Interactions ---
    interface ClockInteractionListener {
        /** Called when the user finishes dragging a hand, providing the calculated time. */
        fun onTimeManuallySet(clockId: Int, newTime: LocalTime)
        /** Called when the user starts (isDragging=true) or stops (isDragging=false) dragging a hand. */
        fun onDragStateChanged(clockId: Int, isDragging: Boolean)
    }
    var interactionListener: ClockInteractionListener? = null

    // --- Configuration State (Configurable via setters) ---
    private var clockId: Int = -1
    private var isAnalog: Boolean = false
        private set // Private setter
    private var clockColor: Int = Color.WHITE
    private var is24Hour: Boolean = false
    private var timeZoneId: ZoneId = ZoneId.systemDefault() // Needed for formatting
    private var displaySeconds: Boolean = true

    // --- State for Display ---
    private var displayedTime: LocalTime = LocalTime.MIN // Holds the last time received

    // --- Views for Analog Mode (Set externally) ---
    // These are the SVG views from the inflated layout (view_floating_clock_analog.xml)
    private var clockFaceImageView: SVGImageView? = null
    private var hourHandImageView: SVGImageView? = null
    private var minuteHandImageView: SVGImageView? = null
    private var secondHandImageView: SVGImageView? = null

    // --- Drawing and Formatting Tools ---
    private lateinit var timeFormatter: DateTimeFormatter
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // Set initial size, will be updated in onDraw/setters
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 48f, resources.displayMetrics)
    }
    private val bounds = Rect() // Needed for digital text bounds

    // --- Touch Handling State ---
    private var lastTouchAngle: Float = 0f
    private var currentlyMovingHand: Hand? = null
    private enum class Hand { HOUR, MINUTE, SECOND }

    // Define the fixed colors from ClockSettingsActivity for mapping
    private object PaletteColors {
        val WHITE = Color.WHITE
        val BLACK = Color.BLACK
        val GOLDENROD = 0xFFDAA520.toInt()
        val TEAL = 0xFF008080.toInt()
        val LIGHT_BLUE = 0xFFADD8E6.toInt()
        val VIOLET = 0xFFEE82EE.toInt()
    }

    // --- Initialization ---
    init {
        // Initialize formatter and paint based on default config state
        updateTimeFormat()
        setPaintColors()

        // Set touch listener (logic remains similar, but relies on external state)
        setOnTouchListener(handTouchListener)
    }

    // --- Public Method for Updating Display ---
    /**
     * Updates the time displayed by the clock. Call this externally (e.g., from Service observing ViewModel).
     * @param timeToDisplay The LocalTime to display.
     */
    fun updateDisplayTime(timeToDisplay: LocalTime) {
        this.displayedTime = timeToDisplay
        if (isAnalog) {
            updateAnalogHands() // Update SVG rotations based on displayedTime
        } else {
            invalidate() // Triggers onDraw for digital based on displayedTime
        }
    }

    // --- Public Configuration Methods ---

    fun setClockId(id: Int) {
        this.clockId = id
    }

    fun setClockMode(isAnalogMode: Boolean) {
        if (isAnalog != isAnalogMode) {
            isAnalog = isAnalogMode
            updateTimeFormat() // Update format (e.g., AM/PM)
            updateAnalogViewVisibility() // Crucial for switching modes
            setPaintColors()
            invalidate() // Request redraw
        }
    }

    fun setClockColor(color: Int) {
        if (clockColor != color) {
            clockColor = color
            setPaintColors()
            invalidate()
        }
    }

    fun setIs24HourFormat(is24: Boolean) {
        if (is24Hour != is24) {
            is24Hour = is24
            updateTimeFormat()
            if (isAnalog) {
                updateNumberVisibility() // Update SVG number visibility
            }
            invalidate()
        }
    }

    fun setClockTimeZone(zoneId: ZoneId) {
        if (this.timeZoneId != zoneId) {
            this.timeZoneId = zoneId
            updateTimeFormat()
            // No immediate time update needed here, relies on next updateDisplayTime call
            invalidate()
        }
    }

    fun setDisplaySeconds(display: Boolean) {
        if (displaySeconds != display) {
            displaySeconds = display
            updateTimeFormat()
            if (isAnalog) {
                secondHandImageView?.visibility = if (display) View.VISIBLE else View.GONE
            }
            invalidate()
        }
    }

    /**
     * Sets the external SVGImageViews used for the analog display.
     * Should be called by the Service after inflating the analog layout.
     */
    fun setAnalogImageViews(
        clockFace: SVGImageView?, hourHand: SVGImageView?, minuteHand: SVGImageView?, secondHand: SVGImageView?
    ) {
        this.clockFaceImageView = clockFace
        this.hourHandImageView = hourHand
        this.minuteHandImageView = minuteHand
        this.secondHandImageView = secondHand
        updateAnalogViewVisibility()
        updateNumberVisibility()
        updateAnalogColors()
        updateAnalogHands() // Set initial hand positions based on current displayedTime
    }

    // --- Internal Helper Methods (Remain mostly the same) ---

    private fun setPaintColors() { // Remains the same
        val handColor = getHandColorForBackground(clockColor)
        textPaint.color = handColor

        // Update external SVG colors if they are being used
        if (clockFaceImageView != null) {
            updateAnalogColors()
        }
    }

    /**
     * Determines the appropriate hand color (black or white) for contrast
     * against the predefined background colors using direct mapping.
     */
    private fun getHandColorForBackground(backgroundColor: Int): Int {
        return when (backgroundColor) {
            PaletteColors.WHITE,
            PaletteColors.GOLDENROD, // Light enough for black hands
            PaletteColors.TEAL,        // Dark enough for white hands? Let's test - maybe black? Assume black for now.
            PaletteColors.LIGHT_BLUE   // Light enough for black hands
                -> Color.BLACK // Use black hands on light backgrounds

            PaletteColors.BLACK,
            PaletteColors.VIOLET       // Dark enough for white hands
                -> Color.WHITE // Use white hands on dark backgrounds

            else -> {
                // Fallback for unexpected colors (shouldn't happen with fixed palette)
                // Calculate luminance as a safe default
                Log.w("ClockView", "Unexpected background color: ${Integer.toHexString(backgroundColor)}. Using luminance check.")
                if (Color.luminance(backgroundColor) > 0.5) Color.BLACK else Color.WHITE
            }
        }
    }

    private fun updateTimeFormat() { // Remains the same
        val locale = Locale.getDefault()
        val basePattern = if (is24Hour) "HH:mm" else "hh:mm"
        val patternWithSeconds = if (is24Hour) "HH:mm:ss" else "hh:mm:ss"
        timeFormatter = DateTimeFormatter
            .ofPattern(if (displaySeconds) patternWithSeconds else basePattern, locale)
            // .withZone(timeZoneId)
    }

    // --- Drawing Logic (Using displayedTime) ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isAnalog) {
           // Only draw if digital mode is active
            drawDigitalClock(canvas)
        }
        // Analog drawing is handled by external SVGImageViews updated in updateAnalogHands()
    }

    private fun drawDigitalClock(canvas: Canvas) {
        // Format the externally provided time
        val formattedTime = try {
            displayedTime.format(timeFormatter.withZone(timeZoneId)) // Ensure formatter uses correct zone
        } catch (e: Exception) {
            Log.e("ClockView", "Error formatting time: $displayedTime", e)
            "--:--" // Fallback display
        }


        // Adjust text size based on available width (simple example)
        val availableWidth = width - paddingLeft - paddingRight
        textPaint.textSize = getDigitalTextSize(availableWidth)

        // Draw background color for the view area
        canvas.drawColor(clockColor)

        // Calculate position to draw centered text
        textPaint.getTextBounds(formattedTime, 0, formattedTime.length, bounds)
        val x = width / 2f
        val y = height / 2f - bounds.exactCenterY() // Center vertically

        // Draw the main time string
        canvas.drawText(formattedTime, x, y, textPaint)

        // Draw AM/PM indicator if needed
        if (!is24Hour) {
            val amPmFormatter = DateTimeFormatter.ofPattern("a", Locale.getDefault())
            val amPmString = try { displayedTime.format(amPmFormatter) } catch (e: Exception) { "" }
            val amPmPaint = Paint(textPaint).apply { textSize *= 0.4f } // Smaller size for AM/PM
            val mainTextWidth = textPaint.measureText(formattedTime)
            val amPmTextWidth = amPmPaint.measureText(amPmString)
            val padding = context.dpToPx(4)

            // Position AM/PM to the right of the main time
            val amPmX = x + (mainTextWidth / 2f) + (amPmTextWidth / 2f) + padding
            val amPmY = y // Align vertically with main time

            canvas.drawText(amPmString, amPmX, amPmY, amPmPaint)
        }
    }

    // Simple text scaling for digital clock
    private fun getDigitalTextSize(availableWidth: Int): Float {
        // Start with a base size relative to height or width
        var textSize = min(width, height) * 0.4f // Example: 40% of smaller dimension
        val minSize = context.dpToPx(12).toFloat() // Minimum reasonable text size

        // Reduce size until it fits (basic approach)
        textPaint.textSize = textSize
        val textWidth = textPaint.measureText(if (displaySeconds) "00:00:00" else "00:00") // Measure sample text
        val maxWidth = availableWidth * 0.9f // Use 90% of width

        while (textWidth > maxWidth && textSize > minSize) {
            textSize *= 0.95f // Reduce size slightly
            textPaint.textSize = textSize
            // Re-measure (approximation, actual text might vary slightly)
            val newWidth = textPaint.measureText(if (displaySeconds) "00:00:00" else "00:00")
            if (newWidth <= maxWidth) break // Stop if it fits
        }
        return max(textSize, minSize) // Return calculated size, but not less than min
    }

    // --- Analog SVG Handlers ---

    private fun updateAnalogViewVisibility() {
        val visibility = if (isAnalog) View.VISIBLE else View.GONE
        clockFaceImageView?.visibility = visibility
        hourHandImageView?.visibility = visibility
        minuteHandImageView?.visibility = visibility
        secondHandImageView?.visibility = if (isAnalog && displaySeconds) View.VISIBLE else View.GONE
    }

    private fun updateAnalogColors() {
        if (!isAnalog) return

        val handColor = getHandColorForBackground(clockColor) // Gets black/white based on mapping
        val faceColor = clockColor

        val handColorFilter = PorterDuffColorFilter(handColor, PorterDuff.Mode.SRC_IN)
        val faceColorFilter = PorterDuffColorFilter(faceColor, PorterDuff.Mode.SRC_IN)

        clockFaceImageView?.setColorFilter(faceColorFilter)
        hourHandImageView?.setColorFilter(handColorFilter)
        minuteHandImageView?.setColorFilter(handColorFilter)
        secondHandImageView?.setColorFilter(handColorFilter)
    }

    private fun updateNumberVisibility() {
        // Only proceed if in analog mode and the face view exists
        if (!isAnalog || clockFaceImageView == null) return

        clockFaceImageView?.svg?.let { svg ->
            try {
                // Attempt to find the groups by ID
                val twelveHourGroup = svg.getElementById("twelveHourNumbers")
                val twentyFourHourGroup = svg.getElementById("twentyFourHourNumbers")

                if (twelveHourGroup == null || twentyFourHourGroup == null) {
                    Log.w("ClockView", "SVG number groups ('twelveHourNumbers' or 'twentyFourHourNumbers') not found in clock_face.xml")
                    return@let // Exit if groups aren't found
                }

                // Set visibility based on the is24Hour flag
                twelveHourGroup.setAttribute("visibility", if (is24Hour) "hidden" else "visible")
                twentyFourHourGroup.setAttribute("visibility", if (is24Hour) "visible" else "hidden")

                // Invalidate the SVGImageView to force redraw with updated visibility
                clockFaceImageView?.invalidate()
                Log.d("ClockView", "Updated SVG number visibility for 24h=$is24Hour")

            } catch (e: Exception) {
                // Catch potential errors during SVG manipulation (e.g., parsing issues)
                Log.e("ClockView", "Error accessing or modifying SVG elements for number visibility", e)
            }
        } ?: Log.w("ClockView", "SVG object is null, cannot update number visibility.")
    }

    /** Updates the rotation of the external SVG hand views based on displayedTime */
    private fun updateAnalogHands() {
        // Only update if in analog mode and views are set
        if (!isAnalog || hourHandImageView == null || minuteHandImageView == null || secondHandImageView == null) {
            return
        }

        // Use the externally provided displayedTime
        val hour = displayedTime.hour
        val minute = displayedTime.minute
        val second = displayedTime.second
        val nano = displayedTime.nano // For smoother second hand if desired

        // Calculate angles (same logic as before)
        // Hour hand moves 360 degrees in 12 hours (30 deg/hr) + fractional movement for minutes/seconds
        val hourAngle = ((hour % 12 + minute / 60f + second / 3600f) * 30f) % 360
        // Minute hand moves 360 degrees in 60 minutes (6 deg/min) + fractional movement for seconds
        val minuteAngle = ((minute + second / 60f) * 6f) % 360
        // Second hand moves 360 degrees in 60 seconds (6 deg/sec) + fractional movement for nanos
        val secondAngle = ((second + nano / 1_000_000_000f) * 6f) % 360

        // Apply rotations to the ImageViews
        hourHandImageView?.rotation = hourAngle
        minuteHandImageView?.rotation = minuteAngle
        secondHandImageView?.rotation = secondAngle
    }

    // --- Touch Handling Logic (Remains similar, but no internal pause/play) ---
    private val handTouchListener = OnTouchListener { v, event ->
        // Only handle touch if in analog mode with SVGs available and listener set
        if (!isAnalog || hourHandImageView == null || minuteHandImageView == null || interactionListener == null || clockId == -1) {
            return@OnTouchListener false
        }

        val centerX = width / 2f
        val centerY = height / 2f
        val x = event.x
        val y = event.y
        val radius = min(centerX, centerY) * 0.8f // Approximate interactive radius

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val touchAngle = calculateAngle(centerX, centerY, x, y)
                val dist = distance(centerX, centerY, x, y)

                // Get current visual angles
                val hourAngle = hourHandImageView?.rotation ?: 0f
                val minuteAngle = minuteHandImageView?.rotation ?: 0f
                val secondAngle = secondHandImageView?.rotation ?: 0f
                val angleThreshold = 15f // Touch tolerance

                currentlyMovingHand = null // Reset

                // Determine which hand is touched (check proximity to visual angle)
                // Check second hand first if displayed
                if (displaySeconds && secondHandImageView?.isVisible == true &&
                    abs(angleDifference(touchAngle, secondAngle)) < angleThreshold && dist > radius * 0.4f) { // Outer range for thin second hand
                    currentlyMovingHand = Hand.SECOND
                } else if (abs(angleDifference(touchAngle, minuteAngle)) < angleThreshold && dist > radius * 0.3f) { // Mid range for minute hand
                    currentlyMovingHand = Hand.MINUTE
                } else if (abs(angleDifference(touchAngle, hourAngle)) < angleThreshold && dist > radius * 0.2f) { // Inner range for hour hand
                    currentlyMovingHand = Hand.HOUR
                }

                if (currentlyMovingHand != null) {
                    Log.d("ClockView", "Hand drag started: $currentlyMovingHand on clock $clockId")
                    lastTouchAngle = touchAngle
                    // Notify listener that drag started (ViewModel will handle pausing)
                    interactionListener?.onDragStateChanged(clockId, true)
                    return@OnTouchListener true // Consume event: started dragging a hand
                } else {
                    return@OnTouchListener false // Did not touch a hand, allow window drag
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentlyMovingHand != null) {
                    val currentTouchAngle = calculateAngle(centerX, centerY, x, y)
                    val deltaAngle = angleDifference(currentTouchAngle, lastTouchAngle)

                    // Apply visual rotation directly to the hand being dragged
                    when (currentlyMovingHand) {
                        Hand.HOUR -> hourHandImageView?.rotation = (hourHandImageView?.rotation ?: 0f) + deltaAngle
                        Hand.MINUTE -> minuteHandImageView?.rotation = (minuteHandImageView?.rotation ?: 0f) + deltaAngle
                        Hand.SECOND -> secondHandImageView?.rotation = (secondHandImageView?.rotation ?: 0f) + deltaAngle
                    }
                    lastTouchAngle = currentTouchAngle
                    return@OnTouchListener true // Consume event: dragging hand
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (currentlyMovingHand != null) {
                    Log.d("ClockView", "Hand drag ended: $currentlyMovingHand on clock $clockId")
                    // Calculate the final time based on the final visual angles
                    val finalTime = calculateTimeFromAngles()
                    // Notify the listener about the manually set time
                    interactionListener?.onTimeManuallySet(clockId, finalTime)
                    // Notify listener that drag ended (ViewModel might handle resuming)
                    interactionListener?.onDragStateChanged(clockId, false)
                    currentlyMovingHand = null // Reset dragging state
                    return@OnTouchListener true // Consume event: finished hand drag
                }
            }
        }
        // If no conditions met or not consumed above
        return@OnTouchListener false
    }

    /**
     * Calculates the LocalTime based on the current rotation angles of the hand ImageViews.
     * This provides a best guess; the ViewModel might need to refine AM/PM or 24h context.
     */
    private fun calculateTimeFromAngles(): LocalTime {
        // Ensure angles are positive within 0-360 range
        val rawHourAngle = (hourHandImageView?.rotation ?: 0f).mod(360f)
        val rawMinuteAngle = (minuteHandImageView?.rotation ?: 0f).mod(360f)
        val rawSecondAngle = (secondHandImageView?.rotation ?: 0f).mod(360f)

        // Convert angles to time components (simplified)
        val seconds = roundToInt(rawSecondAngle / 6f).mod(60)
        val minutes = roundToInt(rawMinuteAngle / 6f).mod(60)

        // Hour calculation needs care due to minute influence and 12 vs 24 ambiguity
        // Estimate hour component (0-11.99~) based purely on hour hand angle
        val hourComponent = rawHourAngle / 30f
        // Let's round for now. ViewModel needs to determine AM/PM or true 24h context.
        var hour12 = roundToInt(hourComponent).mod(12)
        if (hour12 == 0) hour12 = 12 // Display 12 instead of 0

        // Construct a time. Defaulting to current displayed time's AM/PM for hour resolution.
        // ViewModel MUST refine this based on context if 24h accuracy across noon/midnight is critical.
        var hour24 = hour12
        if (displayedTime.hour >= 12 && hour12 != 12) { // If currently PM, assume new time is PM unless it's 12
            hour24 += 12
        } else if (displayedTime.hour < 12 && hour12 == 12) { // Handle 12 AM case (becomes 0 hour)
            hour24 = 0
        }
        // If it was already > 12 (e.g., angle was near 360), keep it 24h
        if (hour12 != 12 && hourComponent >= 12) hour24 = hour12 + 12

        hour24 = hour24.coerceIn(0, 23) // Ensure valid 0-23 range

        return try {
            LocalTime.of(hour24, minutes, seconds)
        } catch (e: Exception) {
            Log.e("ClockView", "Error creating LocalTime from angles, returning previous displayedTime", e)
            displayedTime // Fallback to last known time
        }
    }

    // --- Touch Helper Functions (Remain the same) ---
    private fun calculateAngle(centerX: Float, centerY: Float, x: Float, y: Float): Float {
        val angleRad = atan2(y - centerY, x - centerX)
        var angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
        if (angleDeg < 0) angleDeg += 360f
        return angleDeg
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return hypot(x2 - x1, y2 - y1)
    }

    private fun angleDifference(angle1: Float, angle2: Float): Float {
        var diff = angle1 - angle2
        while (diff <= -180) diff += 360
        while (diff > 180) diff -= 360
        return diff
    }

