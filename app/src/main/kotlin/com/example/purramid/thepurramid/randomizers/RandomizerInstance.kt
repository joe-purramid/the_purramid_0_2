// RandomizerInstance.kt
package com.example.purramid.thepurramid.randomizers

import java.util.UUID

// Data class to hold the state of a Randomizer instance
data class RandomizerInstance(
    val instanceId: UUID = UUID.randomUUID(),
    var spinSettings: SpinSettings = SpinSettings()
    // Add other mode-specific settings as needed
)