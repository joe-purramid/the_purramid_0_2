// ScreenMaskState.kt
package com.example.purramid.thepurramid.screen_mask

data class ScreenMaskState(
    val instanceId: Int = 0,
    var x: Int = 0,
    var y: Int = 0,
    var width: Int = -1, // -1 for default/match_parent initially
    var height: Int = -1, // -1 for default/match_parent initially
    var isLocked: Boolean = false,
    var billboardImageUri: String? = null, // Store URI as String
    var isBillboardVisible: Boolean = false,
    var isControlsVisible: Boolean = true // To manage visibility of control buttons on the mask
)