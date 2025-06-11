// TrafficLightStateEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "traffic_light_state")
data class TrafficLightStateEntity(
    @PrimaryKey // Using instanceId passed during creation/loading
    val instanceId: Int,

    // Fields mirroring TrafficLightState
    val currentMode: String, // Store enum TrafficLightMode as String
    val orientation: String, // Store enum Orientation as String
    val isBlinkingEnabled: Boolean,
    val activeLight: String?, // Store enum LightColor? as String?
    val isSettingsOpen: Boolean, // Usually transient, but store if needed across restarts
    val isMicrophoneAvailable: Boolean, // Store last known state
    val numberOfOpenInstances: Int, // Store count (might be managed globally later)
    val responsiveModeSettingsJson: String, // Store ResponsiveModeSettings as JSON

    // Add fields for window persistence
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1, // Use -1 for WRAP_CONTENT or default
    val windowHeight: Int = -1 // Use -1 for WRAP_CONTENT or default
)