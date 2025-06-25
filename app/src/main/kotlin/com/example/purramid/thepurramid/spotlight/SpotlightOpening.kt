// SpotlightOpening.kt
package com.example.purramid.thepurramid.spotlight

/**
 * Data class representing a spotlight opening (hole) in the overlay.
 * This is used for UI rendering and state management.
 */
data class SpotlightOpening(
    val openingId: Int,
    var centerX: Float,
    var centerY: Float,
    var radius: Float,
    var width: Float,
    var height: Float,
    var size: Float,
    var shape: Shape,
    var isLocked: Boolean = false,
    var displayOrder: Int = 0
) {
    enum class Shape {
        CIRCLE, OVAL, SQUARE, RECTANGLE
    }

    companion object {
        /**
         * Creates a default circular opening at the specified position
         */
        fun createDefault(
            openingId: Int = 0,
            centerX: Float,
            centerY: Float,
            radius: Float = 125f // 250px diameter as per spec
        ): SpotlightOpening {
            return SpotlightOpening(
                openingId = openingId,
                centerX = centerX,
                centerY = centerY,
                radius = radius,
                width = radius * 2,
                height = radius * 2,
                size = radius * 2,
                shape = Shape.CIRCLE,
                isLocked = false,
                displayOrder = 0
            )
        }
    }
}