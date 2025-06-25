// TrafficLightStateEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "traffic_light_state")
data class TrafficLightStateEntity(
    @PrimaryKey
    val instanceId: Int,
    val currentMode: String,
    val orientation: String,
    val isBlinkingEnabled: Boolean,
    val activeLight: String?,
    val isSettingsOpen: Boolean,
    val isMicrophoneAvailable: Boolean,
    val numberOfOpenInstances: Int,
    val responsiveModeSettingsJson: String,

    // Add fields for Timed Mode UI settings
    val showTimeRemaining: Boolean,
    val showTimeline: Boolean,

    // Add fields for window persistence
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1,
    val windowHeight: Int = -1
)