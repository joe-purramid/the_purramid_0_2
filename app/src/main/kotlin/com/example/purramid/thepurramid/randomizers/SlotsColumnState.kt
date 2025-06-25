// SlotsColumnState.kt
package com.example.purramid.thepurramid.randomizers

import java.util.UUID

/**
 * Represents the state of a single column in the Slots randomizer mode.
 *
 * @param columnIndex The zero-based index of this column (0, 1, 2, ...).
 * @param selectedListId The ID of the SpinListEntity currently assigned to this column. Null if none selected.
 * @param isLocked Whether this column's result is locked and should not spin.
 * @param currentItemId The ID of the SpinItemEntity currently displayed as the result for this column. Null if not yet spun or list is empty.
 */
data class SlotsColumnState(
    val columnIndex: Int,
    var selectedListId: UUID? = null,
    var isLocked: Boolean = false,
    var currentItemId: UUID? = null
    // Add any other column-specific state needed in the future
)