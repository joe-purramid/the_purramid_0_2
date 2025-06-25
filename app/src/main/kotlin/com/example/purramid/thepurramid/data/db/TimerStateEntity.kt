// TimerStateEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.purramid.thepurramid.timers.TimerType
import com.example.purramid.thepurramid.ui.PurramidPalette
import java.util.UUID

@Entity(tableName = "timer_state")
data class TimerStateEntity(
    @PrimaryKey
    val timerId: Int,
    val uuid: String = UUID.randomUUID().toString(),
    val type: String = TimerType.STOPWATCH.name,
    val initialDurationMillis: Long = 0L,
    val currentMillis: Long = 0L,
    val isRunning: Boolean = false,
    val lapsJson: String = "[]",
    val showCentiseconds: Boolean = true,
    val playSoundOnEnd: Boolean = false,
    val overlayColor: Int = PurramidPalette.WHITE.colorInt,
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1,
    val windowHeight: Int = -1,
    // Nested timer fields
    val isNested: Boolean = false,
    val nestedX: Int = -1,
    val nestedY: Int = -1,
    // Sound fields
    val soundsEnabled: Boolean = false,
    val selectedSoundUri: String? = null,
    val musicUrl: String? = null,
    val recentMusicUrlsJson: String = "[]",
    // Lap display
    val showLapTimes: Boolean = false
)