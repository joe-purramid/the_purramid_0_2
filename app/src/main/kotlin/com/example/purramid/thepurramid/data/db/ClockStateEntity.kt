// ClockStateEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.purramid.thepurramid.clock.ClockOverlayService // For default values if needed
import java.time.ZoneId // Import for default zone ID

@Entity(tableName = "clock_state")
data class ClockStateEntity(
    @PrimaryKey // Use the unique clock ID managed by the service
    val clockId: Int,

    val timeZoneId: String = ZoneId.systemDefault().id, // Store ZoneId as String, provide default
    val isPaused: Boolean = false,
    val displaySeconds: Boolean = true,
    val is24Hour: Boolean = false,
    val clockColor: Int = android.graphics.Color.WHITE, // Default color
    val mode: String = "digital", // "digital" or "analog"
    val isNested: Boolean = false,

    // Store position and size for the overlay window
    val windowX: Int = 0, // Default position
    val windowY: Int = 0, // Default position
    val windowWidth: Int = -1, // Default to WRAP_CONTENT indicator
    val windowHeight: Int = -1, // Default to WRAP_CONTENT indicator

    // Store manually set time if needed (e.g., as total seconds of the day)
    val manuallySetTimeSeconds: Long? = null // Nullable Long

)