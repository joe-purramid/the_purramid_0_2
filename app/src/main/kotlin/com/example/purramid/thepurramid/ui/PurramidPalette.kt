// PurramidPalette.kt
package com.example.purramid.thepurramid.ui

import android.graphics.Color

/**
 * Defines the standard Purramid color palette for consistent use across app-intents.
 */
object PurramidPalette {
    data class NamedColor(val name: String, val colorInt: Int, val isDefault: Boolean = false)

    // Define the core palette colors matching ClockView
    val WHITE = NamedColor("White", Color.WHITE)
    val BLACK = NamedColor("Black", Color.BLACK)
    val GOLDENROD = NamedColor("Goldenrod", 0xFFDAA520.toInt())
    val TEAL = NamedColor("Teal", 0xFF008080.toInt()) // This was Teal in ClockSettings
    val LIGHT_BLUE = NamedColor("Light Blue", 0xFFADD8E6.toInt())
    val VIOLET = NamedColor("Violet", 0xFFEE82EE.toInt())

    /**
     * The list of colors available in the general palette pickers (e.g., for Clock, Timers).
     */
    val appStandardPalette: List<NamedColor> = listOf(
        WHITE,
        BLACK,
        GOLDENROD,
        TEAL,
        LIGHT_BLUE,
        VIOLET
    )

    // Special color to represent the default/no custom color state for dice
    // This allows the die's original SVG color or a transparent tint to be used.
    val DEFAULT_DIE_COLOR = NamedColor("Default", Color.TRANSPARENT, true) // Color.TRANSPARENT is a good choice for "no override"

    /**
     * The list of colors available in the palette picker for dice.
     * Includes the "Default" option first.
     */
    val dicePaletteColors: List<NamedColor> = listOf(
        DEFAULT_DIE_COLOR, // Represents using the die's intrinsic color
        WHITE,
        BLACK,
        GOLDENROD,
        TEAL,
        LIGHT_BLUE,
        VIOLET
        // Add other Purramid brand colors here if they become available
    )

    /**
     * Default color to show for a die's color preview square if no specific color is configured.
     * This is distinct from DEFAULT_DIE_COLOR which is an *option* in the picker.
     * This could be a light grey or white to ensure the colorSquareView is visible.
     */
    const val UI_DEFAULT_PREVIEW_COLOR_INT = Color.LTGRAY // Or Color.WHITE, for UI preview elements
}