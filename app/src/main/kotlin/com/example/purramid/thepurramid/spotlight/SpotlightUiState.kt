// SpotlightUiState.kt
package com.example.purramid.thepurramid.spotlight

/**
 * Represents the UI state for the Spotlight overlay.
 * A single overlay can contain multiple openings (holes).
 *
 * @param instanceId The service instance ID (1-4)
 * @param openings The list of spotlight openings to display
 * @param showControls Whether to show control buttons
 * @param isAnyLocked True if any opening is locked
 * @param areAllLocked True if all openings are locked
 * @param canAddMore True if more openings can be added (max 4)
 * @param isLoading True during database operations
 * @param error Error message to display, if any
 */
data class SpotlightUiState(
    val instanceId: Int = 0,
    val openings: List<SpotlightOpening> = emptyList(),
    val showControls: Boolean = true,
    val isAnyLocked: Boolean = false,
    val areAllLocked: Boolean = false,
    val canAddMore: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null
) {
    companion object {
        const val MAX_OPENINGS = 4
    }

    /**
     * Computed property to check if we can add more openings
     */
    val isAtMaxCapacity: Boolean
        get() = openings.size >= MAX_OPENINGS
}