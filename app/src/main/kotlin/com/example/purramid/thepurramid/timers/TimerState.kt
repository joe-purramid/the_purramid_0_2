// TimerState.kt
package com.example.purramid.thepurramid.timers

import com.example.purramid.thepurramid.ui.PurramidPalette

data class TimerState(
    val timerId: Int = 0, // Or UUID if preferred
    val type: TimerType = TimerType.STOPWATCH,
    val initialDurationMillis: Long = 0L, // For countdown
    val currentMillis: Long = 0L, // Elapsed for stopwatch, Remaining for countdown
    val isRunning: Boolean = false,
    val laps: List<Long> = emptyList(), // For stopwatch
    val showCentiseconds: Boolean = true,
    val playSoundOnEnd: Boolean = false, // For countdown
    val overlayColor: Int = PurramidPalette.WHITE.colorInt,
    // Add window position/size if needed for persistence
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1, // -1 for WRAP_CONTENT
    val windowHeight: Int = -1 // -1 for WRAP_CONTENT
)