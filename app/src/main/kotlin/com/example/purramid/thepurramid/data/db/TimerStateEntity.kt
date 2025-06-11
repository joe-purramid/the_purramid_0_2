// TimerStateEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.purramid.thepurramid.timers.TimerType
import com.example.purramid.thepurramid.ui.PurramidPalette

@Entity(tableName = "timer_state")
data class TimerStateEntity(
    @PrimaryKey
    val timerId: Int,
    val type: String = TimerType.STOPWATCH.name, // Store enum as String
    val initialDurationMillis: Long = 0L,
    val currentMillis: Long = 0L,
    val isRunning: Boolean = false,
    val lapsJson: String = "[]", // Store list as JSON
    val showCentiseconds: Boolean = true,
    val playSoundOnEnd: Boolean = false,
    val overlayColor: Int = PurramidPalette.WHITE.colorInt,
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1,
    val windowHeight: Int = -1
)