// CountdownDurationPickerView.kt
package com.example.purramid.thepurramid.timers.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.example.purramid.thepurramid.R
import kotlin.math.abs
import kotlin.math.roundToInt

class CountdownDurationPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var hours = 0
    private var minutes = 0
    private var seconds = 0
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.resources.getDimension(R.dimen.timer_duration_text_size)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    
    private val colonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.resources.getDimension(R.dimen.timer_duration_text_size)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = context.resources.getDimension(R.dimen.timer_arrow_size)
    }
    
    private var digitWidth = 0f
    private var digitSpacing = 0f
    private var arrowPadding = 0f
    
    private val gestureDetector = GestureDetector(context, GestureListener())
    private var activeDigitIndex = -1
    private var animatingDigits = mutableMapOf<Int, ValueAnimator>()
    
    var onDurationChangeListener: ((hours: Int, minutes: Int, seconds: Int) -> Unit)? = null
    
    init {
        calculateDimensions()
    }
    
    private fun calculateDimensions() {
        digitWidth = textPaint.measureText("0")
        digitSpacing = digitWidth * 0.3f
        arrowPadding = context.resources.getDimension(R.dimen.timer_arrow_padding)
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (digitWidth * 6 + digitSpacing * 4 + paddingLeft + paddingRight).toInt()
        val desiredHeight = (textPaint.textSize * 3 + arrowPadding * 4 + paddingTop + paddingBottom).toInt()
        
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        
        setMeasuredDimension(width, height)
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val baselineY = centerY + textPaint.textSize / 3
        
        // Calculate positions for each digit
        val totalWidth = digitWidth * 6 + digitSpacing * 4
        val startX = centerX - totalWidth / 2f + digitWidth / 2f
        
        // Draw time digits
        val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        val digits = timeString.toCharArray()
        
        var currentX = startX
        digits.forEachIndexed { index, digit ->
            if (digit == ':') {
                colonPaint.color = textPaint.color
                canvas.drawText(digit.toString(), currentX, baselineY, colonPaint)
                currentX += digitSpacing
            } else {
                val digitIndex = when {
                    index < 2 -> index // Hours
                    index in 3..4 -> index - 1 // Minutes (skip colon)
                    else -> index - 2 // Seconds (skip two colons)
                }
                
                // Highlight active digit
                textPaint.color = if (digitIndex == activeDigitIndex) {
                    ContextCompat.getColor(context, R.color.timer_active_digit)
                } else {
                    ContextCompat.getColor(context, R.color.timer_normal_digit)
                }
                
                canvas.drawText(digit.toString(), currentX, baselineY, textPaint)
                
                // Draw arrows for digits
                if (digit != ':') {
                    drawArrows(canvas, currentX, baselineY, digitIndex)
                }
                
                currentX += digitWidth
            }
        }
    }
    
    private fun drawArrows(canvas: Canvas, x: Float, baselineY: Float, digitIndex: Int) {
        arrowPaint.color = ContextCompat.getColor(context, R.color.timer_arrow_color)
        
        // Up arrow
        val upArrowY = baselineY - textPaint.textSize - arrowPadding
        canvas.drawText("▲", x, upArrowY, arrowPaint)
        
        // Down arrow
        val downArrowY = baselineY + textPaint.textSize + arrowPadding
        canvas.drawText("▼", x, downArrowY, arrowPaint)
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }
    
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        private var initialY = 0f
        private var accumulatedDelta = 0f
        
        override fun onDown(e: MotionEvent): Boolean {
            initialY = e.y
            accumulatedDelta = 0f
            activeDigitIndex = getDigitIndexFromX(e.x)
            invalidate()
            return true
        }
        
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (activeDigitIndex == -1) return false
            
            accumulatedDelta += distanceY / 50f // Adjust sensitivity
            
            if (abs(accumulatedDelta) >= 1) {
                val delta = accumulatedDelta.toInt()
                accumulatedDelta -= delta
                
                adjustDigit(activeDigitIndex, -delta) // Negative because scroll up = increase
                invalidate()
            }
            
            return true
        }
        
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (activeDigitIndex == -1) return false
            
            val delta = (-velocityY / 1000).roundToInt().coerceIn(-9, 9)
            if (delta != 0) {
                animateDigitChange(activeDigitIndex, delta)
            }
            
            return true
        }
        
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val digitIndex = getDigitIndexFromX(e.x)
            val centerY = height / 2f
            
            // Check if tap is on arrow
            if (e.y < centerY - textPaint.textSize) {
                // Up arrow tapped
                adjustDigit(digitIndex, 1)
                invalidate()
            } else if (e.y > centerY + textPaint.textSize) {
                // Down arrow tapped
                adjustDigit(digitIndex, -1)
                invalidate()
            }
            
            return true
        }
    }
    
    private fun getDigitIndexFromX(x: Float): Int {
        val centerX = width / 2f
        val totalWidth = digitWidth * 6 + digitSpacing * 4
        val startX = centerX - totalWidth / 2f
        
        var currentX = startX
        var digitIndex = 0
        
        for (i in 0..7) {
            when (i) {
                2, 5 -> currentX += digitSpacing // Skip colons
                else -> {
                    if (x >= currentX && x < currentX + digitWidth) {
                        return digitIndex
                    }
                    currentX += digitWidth
                    digitIndex++
                }
            }
        }
        
        return -1
    }
    
    private fun adjustDigit(digitIndex: Int, delta: Int) {
        when (digitIndex) {
            0 -> adjustHoursTens(delta)
            1 -> adjustHoursOnes(delta)
            2 -> adjustMinutesTens(delta)
            3 -> adjustMinutesOnes(delta)
            4 -> adjustSecondsTens(delta)
            5 -> adjustSecondsOnes(delta)
        }
        
        onDurationChangeListener?.invoke(hours, minutes, seconds)
    }
    
    private fun animateDigitChange(digitIndex: Int, delta: Int) {
        // Cancel existing animation for this digit
        animatingDigits[digitIndex]?.cancel()
        
        val animator = ValueAnimator.ofFloat(0f, delta.toFloat()).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            var lastValue = 0
            
            addUpdateListener { animation ->
                val currentValue = (animation.animatedValue as Float).toInt()
                val diff = currentValue - lastValue
                if (diff != 0) {
                    adjustDigit(digitIndex, diff)
                    invalidate()
                }
                lastValue = currentValue
            }
        }
        
        animatingDigits[digitIndex] = animator
        animator.start()
    }
    
    private fun adjustHoursTens(delta: Int) {
        val currentTens = hours / 10
        val newTens = (currentTens + delta).coerceIn(0, 9)
        hours = newTens * 10 + (hours % 10)
        if (hours > 99) hours = 99
    }
    
    private fun adjustHoursOnes(delta: Int) {
        val currentOnes = hours % 10
        val newOnes = (currentOnes + delta).let {
            when {
                it < 0 -> it + 10
                it > 9 -> it - 10
                else -> it
            }
        }
        hours = (hours / 10) * 10 + newOnes
        if (hours > 99) hours = 99
    }
    
    private fun adjustMinutesTens(delta: Int) {
        val currentTens = minutes / 10
        val newTens = (currentTens + delta).let {
            when {
                it < 0 -> 5
                it > 5 -> 0
                else -> it
            }
        }
        minutes = newTens * 10 + (minutes % 10)
    }
    
    private fun adjustMinutesOnes(delta: Int) {
        val currentOnes = minutes % 10
        val newOnes = (currentOnes + delta).let {
            when {
                it < 0 -> it + 10
                it > 9 -> it - 10
                else -> it
            }
        }
        minutes = (minutes / 10) * 10 + newOnes
        if (minutes > 59) minutes -= 60
    }
    
    private fun adjustSecondsTens(delta: Int) {
        val currentTens = seconds / 10
        val newTens = (currentTens + delta).let {
            when {
                it < 0 -> 5
                it > 5 -> 0
                else -> it
            }
        }
        seconds = newTens * 10 + (seconds % 10)
    }
    
    private fun adjustSecondsOnes(delta: Int) {
        val currentOnes = seconds % 10
        val newOnes = (currentOnes + delta).let {
            when {
                it < 0 -> it + 10
                it > 9 -> it - 10
                else -> it
            }
        }
        seconds = (seconds / 10) * 10 + newOnes
        if (seconds > 59) seconds -= 60
    }
    
    fun setDuration(hours: Int, minutes: Int, seconds: Int) {
        this.hours = hours.coerceIn(0, 99)
        this.minutes = minutes.coerceIn(0, 59)
        this.seconds = seconds.coerceIn(0, 59)
        invalidate()
    }
    
    fun getDurationMillis(): Long {
        return hours * 3600000L + minutes * 60000L + seconds * 1000L
    }
}