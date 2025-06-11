// TrafficLightState.kt
package com.example.purramid.thepurramid.traffic_light.viewmodel

// Imports from the same package
// No explicit imports needed if they are in the same package,
// but good practice if you move them further.
// import com.example.purramid.thepurramid.traffic_light.viewmodel.TrafficLightMode
// import com.example.purramid.thepurramid.traffic_light.viewmodel.Orientation
// import com.example.purramid.thepurramid.traffic_light.viewmodel.LightColor
// import com.example.purramid.thepurramid.traffic_light.viewmodel.ResponsiveModeSettings

data class TrafficLightState(
    val instanceId: Int = 0,
    val currentMode: TrafficLightMode = TrafficLightMode.MANUAL_CHANGE,
    val orientation: Orientation = Orientation.VERTICAL,
    val isBlinkingEnabled: Boolean = true,
    val activeLight: LightColor? = null,
    val isSettingsOpen: Boolean = false,
    val isMicrophoneAvailable: Boolean = true, // Update later with actual check
    val numberOfOpenInstances: Int = 1,       // Update later when "Add Another" is implemented
    val responsiveModeSettings: ResponsiveModeSettings = ResponsiveModeSettings()
    // Add fields for Timed Mode UI settings
    val showTimeRemaining: Boolean = false, // Default to false
    val showTimeline: Boolean = true,      // Default to true (as per layout)
    // Add fields for window persistence state
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1, // Use -1 for WRAP_CONTENT or default
    val windowHeight: Int = -1 // Use -1 for WRAP_CONTENT or default
)