// ResponsiveSettings.kt
package com.example.purramid.thepurramid.traffic_light.viewmodel

// Represents the min/max values for a single color band in Responsive Mode
// Values can be null if the band is inactive (N/A)
data class DbRange(
    val minDb: Int?,
    val maxDb: Int?
) {
    fun isNa(): Boolean = minDb == null && maxDb == null

    companion object {
        val NA_RANGE = DbRange(null, null)
    }
}

// Holds all adjustable settings for Responsive Mode
data class ResponsiveModeSettings(
    val greenRange: DbRange = DbRange(0, 59),
    val yellowRange: DbRange = DbRange(60, 79),
    val redRange: DbRange = DbRange(80, 120),
    val dangerousSoundAlertEnabled: Boolean = false
)