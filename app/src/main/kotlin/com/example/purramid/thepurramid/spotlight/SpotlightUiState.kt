// SpotlightUiState.kt
package com.example.purramid.thepurramid.spotlight

// Import the data class for the individual spotlight item
import com.example.purramid.thepurramid.spotlight.SpotlightView.Spotlight

/**
 * Represents the overall state for the Spotlight UI.
 *
 * @param spotlights The list of individual spotlight data objects to be displayed.
 * @param globalShape The currently selected shape for new spotlights or global changes.
 * @param isLoading True if the state is currently being loaded (e.g., from the database).
 * @param error A string message describing an error, if one occurred, otherwise null.
 * @param canAddMore True if the user is allowed to add more spotlights (not at max limit).
 */
data class SpotlightUiState(
    val spotlights: List<Spotlight> = emptyList(),
    val globalShape: Spotlight.Shape = Spotlight.Shape.CIRCLE, // Default shape
    val isLoading: Boolean = false,
    val error: String? = null
    // Consider adding other relevant UI state flags if needed, e.g.:
    // val showControls: Boolean = true,
    // val canAddMore: Boolean = true // This might be derived from spotlights.size < MAX
)
