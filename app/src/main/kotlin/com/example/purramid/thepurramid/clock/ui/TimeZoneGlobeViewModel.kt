package com.example.purramid.thepurramid.clock.ui

import android.graphics.Color
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.clock.data.TimeZoneRepository
import io.github.sceneview.math.Rotation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.locationtech.jts.geom.Polygon
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone // Keep for getRawOffset if needed, but prefer ZoneId
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Data class to hold processed info for overlays
data class TimeZoneOverlayInfo(
    val tzid: String,
    val polygons: List<Polygon>,
    val color: Int // Use Android Color Int
)

// Represents the state of the Globe UI
data class TimeZoneGlobeUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val timeZoneOverlays: List<TimeZoneOverlayInfo> = emptyList(),
    val activeTimeZoneId: String? = TimeZone.getDefault().id, // Initial default
    val activeTimeZoneInfo: ActiveZoneDisplayInfo? = null,
    val currentRotation: Rotation = Rotation(0f, 0f, 0f),
    val targetRotation: Rotation? = null
)

data class ActiveZoneDisplayInfo(
    val northernCity: String,
    val southernCity: String,
    val utcOffsetString: String
)

// Enum to represent direction
enum class RotationDirection { LEFT, RIGHT }

@HiltViewModel
class TimeZoneGlobeViewModel @Inject constructor(
    private val repository: TimeZoneRepository
) : ViewModel() {

    private val TAG = "TimeZoneGlobeViewModel"

    private val _uiState = MutableStateFlow(TimeZoneGlobeUiState())
    val uiState: StateFlow<TimeZoneGlobeUiState> = _uiState.asStateFlow()

    // Keep track of raw polygon data separate from UI overlay info
    private var rawTimeZonePolygons: Map<String, List<Polygon>> = emptyMap()
    private var timeZoneOffsets: MutableMap<String, Int> = mutableMapOf() // Offset in seconds

    // Colors for hourly offsets (Red, Yellow, Green, Blue cycle) - Now Int
    private val timeZoneColors = listOf(
        Color.argb(128, 255, 0, 0),   // Red (alpha 0.5)
        Color.argb(128, 255, 255, 0), // Yellow (alpha 0.5)
        Color.argb(128, 0, 255, 0),   // Green (alpha 0.5)
        Color.argb(128, 0, 0, 255)    // Blue (alpha 0.5)
    )
    private val nonHourlyColor = Color.argb(128, 128, 0, 128) // Violet (alpha 0.5) - Placeholder
    private val activeTimeZoneColor = Color.argb(180, 255, 165, 0) // Orange (alpha ~0.7)

    // --- Store approximate center longitudes for relevant offsets (hourly and non-hourly) ---
    private val offsetCenterLongitudes = mutableMapOf<Float, Float>() // Offset (hours) to Longitude (degrees)
    // Sorted list of the keys from the map above
    private var sortedOffsets = listOf<Float>()

    init {
        loadTimeZoneData()
    }

    // Calculate and store current offsets (considering DST) for all loaded time zones
    private fun calculateAllOffsetCenters() {
        offsetCenterLongitudes.clear()
        // Define all offsets we care about stepping through
        val targetOffsets = mutableSetOf<Float>()
        // Add all hourly offsets found
        timeZoneOffsets.values.filter { (it % 3600) == 0 }.forEach { targetOffsets.add(it / 3600f) }
        // Add specific non-hourly offsets needed
        targetOffsets.addAll(listOf(-3.5f, 3.5f, 4.5f, 5.5f, 5.75f, 6.5f, 9.5f, 12.75f))
        // Also ensure standard offsets adjacent to non-hourly ones are present
        targetOffsets.addAll(listOf(-4f, -3f, 3f, 4f, 5f, 6f, 7f, 9f, 10f, 12f, 13f)) // Add neighbors if needed

        // Calculate center longitude for each target offset
        targetOffsets.forEach { offset ->
            // Find all tzid's that currently match this offset (within a small tolerance for floats?)
            // Or, more simply, just estimate based on offset value for now
            // IMPROVEMENT NEEDED: Use city/polygon data for better center calculation!
            val estimatedCenter = (offset * 15.0f).coerceIn(-180f, 180f) // Basic estimate
            offsetCenterLongitudes[offset] = estimatedCenter
            Log.d(TAG, "Center for UTC${offset}: ${estimatedCenter} degrees")
        }

        // Create the sorted list
        sortedOffsets = offsetCenterLongitudes.keys.sorted()
        Log.d(TAG, "Sorted Offsets for Rotation: $sortedOffsets")
    }

    private fun loadTimeZoneData() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val polygonResult = repository.getTimeZonePolygons()
            if (polygonResult.isFailure) {
                // Handle error...
                _uiState.update { it.copy(isLoading = false, error = "Failed to load boundaries") }
                return@launch
            }
            rawTimeZonePolygons = polygonResult.getOrNull() ?: emptyMap()
            calculateAllTimeZoneOffsets()
            calculateAllOffsetCenters()
            processDataForUi()
            updateActiveZoneInfo(_uiState.value.activeTimeZoneId) // Update info for default zone
        }
    }

    // Processes raw polygons and offsets into list of TimeZoneOverlayInfo for the UI
    private fun processDataForUi() {
        val overlays = mutableListOf<TimeZoneOverlayInfo>()
        val activeId = _uiState.value.activeTimeZoneId

        // Group by offset for coloring (similar to previous logic)
        val offsetsGrouped = timeZoneOffsets.entries.groupBy { it.value / 3600.0 }
        val sortedOffsets = offsetsGrouped.keys.sorted()
        val hourlyOffsetMap = sortedOffsets.filter { it == it.toInt().toDouble() }.associateWith { offset ->
            val colorIndex = (offset.toInt() % timeZoneColors.size + timeZoneColors.size) % timeZoneColors.size
            timeZoneColors[colorIndex]
        }

        rawTimeZonePolygons.forEach { (tzId, polygons) ->
            val currentOffsetSeconds = timeZoneOffsets[tzId] ?: return@forEach // Skip if no offset
            val isSystemActiveZone = tzId == activeId
            val currentOffsetHours = currentOffsetSeconds / 3600.0

            val zoneColor: Int = if (isSystemActiveZone) {
                activeTimeZoneColor
            } else {
                if (currentOffsetHours == currentOffsetHours.toInt().toDouble()) {
                    hourlyOffsetMap[currentOffsetHours] ?: nonHourlyColor
                } else {
                    // Placeholder for non-hourly/striped
                    // TODO: Implement logic to find bounding colors if needed for striping later
                    nonHourlyColor
                }
            }
            overlays.add(TimeZoneOverlayInfo(tzId, polygons, zoneColor))
        }

        _uiState.update {
            it.copy(
                isLoading = false,
                timeZoneOverlays = overlays,
                error = null
            )
        }
        Log.d(TAG, "Processed ${overlays.size} overlays for UI.")
    }

    // Call this when the user interacts or system default changes
    fun setActiveTimeZone(tzId: String) {
        if (_uiState.value.activeTimeZoneId != tzId) {
            _uiState.update { it.copy(activeTimeZoneId = tzId) }
            processDataForUi() // Re-process to update active color
            updateActiveZoneInfo(tzId) // Update text info
        }
    }

    // Updates the display text info for the active zone
    private fun updateActiveZoneInfo(timeZoneId: String?) {
        _uiState.update { it.copy(activeTimeZoneInfo = null) }
        if (timeZoneId == null) {
            return
        }

        try {
            val zoneId = ZoneId.of(timeZoneId)
            val now = ZonedDateTime.now(zoneId)
            val offset = now.offset
            val offsetHours = offset.totalSeconds / 3600
            val offsetMinutes = abs((offset.totalSeconds % 3600) / 60)
            val offsetString = when {
                offset.totalSeconds == 0 -> "UTC"
                else -> "UTC${if (offsetHours >= 0) "+" else ""}${offsetHours}${if (offsetMinutes > 0) ":${"%02d".format(offsetMinutes)}" else ""}"
            }

            // Find representative cities for this timezone
            viewModelScope.launch {
                try {
                    val cities = repository.getCitiesForTimeZone(timeZoneId)
                    val northernCities = cities.filter { it.latitude > 0 }.sortedBy { it.latitude }.reversed()
                    val southernCities = cities.filter { it.latitude < 0 }.sortedBy { it.latitude }
                    
                    val northernCity = northernCities.firstOrNull()?.let { "${it.name}, ${it.country}" } ?: "No northern city"
                    val southernCity = southernCities.firstOrNull()?.let { "${it.name}, ${it.country}" } ?: "No southern city"
                    
                    _uiState.update {
                        it.copy(
                            activeTimeZoneInfo = ActiveZoneDisplayInfo(northernCity, southernCity, offsetString)
                        )
                    }
                    Log.d(TAG, "Updated active zone info for: $timeZoneId ($offsetString)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching cities for $timeZoneId", e)
                    // Fallback to basic info
                    val fallbackCity = timeZoneId.substringAfterLast('/', timeZoneId).replace('_', ' ')
                    _uiState.update {
                        it.copy(
                            activeTimeZoneInfo = ActiveZoneDisplayInfo(fallbackCity, "City lookup failed", offsetString)
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error updating info display for $timeZoneId", e)
            _uiState.update { it.copy(activeTimeZoneInfo = null) } // Clear info on error
        }
    }

    // --- New: Calculate approximate center longitude for each WHOLE hour offset ---
    private fun calculateHourlyOffsetCenters() {
        hourlyOffsetCenterLongitudes.clear()
        // Group zones by WHOLE hour offset
        val zonesByHour = timeZoneOffsets.entries
            .filter { (it.value % 3600) == 0 } // Filter for hourly offsets only
            .groupBy { it.value / 3600 } // Group by hour (Int)

        zonesByHour.forEach { (hourOffset, entries) ->
            // Simple approach: Average the longitude of the FIRST city found for each zone in this offset group
            // More complex: Calculate geometric centroid of all polygons in the group
            val representativeLongitudes = entries.mapNotNull { (tzId, _) ->
                // Need city data for this tzId to get a longitude
                // This requires modifying getCitiesForTimeZone or loading city data here
                // For simplicity NOW, let's just assign a rough longitude based on offset
                // (0 hours = 0 degrees, +1 hour = 15 deg East, -1 hour = 15 deg West)
                // THIS IS A CRUDE APPROXIMATION! Replace with better calculation later.
                (hourOffset * 15.0f).coerceIn(-180f, 180f) // Basic estimate
            }

            if (representativeLongitudes.isNotEmpty()) {
                // Handle wrap-around average (e.g., for zones near +/- 180) - complex!
                // Simple average for now:
                val avgLongitude = representativeLongitudes.average().toFloat()
                hourlyOffsetCenterLongitudes[hourOffset] = avgLongitude
                Log.d(TAG, "Center for UTC${hourOffset}: ${avgLongitude} degrees")
            }
        }
        // Manually add edge cases if needed (e.g., UTC+14)
        // hourlyOffsetCenterLongitudes[14] = -150f // Approx longitude for Line Islands
    }


    // --- New: Function called by Activity buttons ---
    // --- Updated: Rotate using the sorted list of all offsets ---
    fun rotateToAdjacentZone(direction: RotationDirection) {
        val currentState = _uiState.value
        if (sortedOffsets.isEmpty() || offsetCenterLongitudes.isEmpty()) {
            Log.w(TAG, "Offset centers not calculated yet.")
            return
        }

        // 1. Get current rotation & longitude
        val currentYRotation = currentState.currentRotation.y
        val currentLongitude = -currentYRotation // Adjust sign if needed

        // 2. Find the *closest* offset in our *complete* sorted list
        val closestOffset = findClosestOffset(currentLongitude) ?: sortedOffsets.first() // Fallback

        // 3. Find the index of this closest offset
        val currentIndex = sortedOffsets.indexOf(closestOffset)
        if (currentIndex == -1) {
            Log.e(TAG, "Could not find index for closest offset $closestOffset")
            return // Should not happen if list/map are populated
        }

        // 4. Determine target index based on direction
        val targetIndex = when (direction) {
            RotationDirection.LEFT -> (currentIndex - 1 + sortedOffsets.size) % sortedOffsets.size
            RotationDirection.RIGHT -> (currentIndex + 1) % sortedOffsets.size
        }
        val targetOffset = sortedOffsets[targetIndex]

        // 5. Get target longitude from the map
        val targetLongitude = offsetCenterLongitudes[targetOffset] ?: run {
            Log.e(TAG, "Could not find center longitude for target offset $targetOffset")
            return // Should not happen
        }

        // 6. Calculate required Y rotation (same logic as before)
        val targetYRotation = -targetLongitude
        val rotationDifference = targetYRotation - currentYRotation
        val shortestRotation = rotationDifference - if (rotationDifference > 180f) 360f else if (rotationDifference < -180f) -360f else 0f
        val finalTargetYRotation = (currentYRotation + shortestRotation)

        // 7. Update State (same logic as before)
        val newRotation = Rotation(
            x = currentState.currentRotation.x,
            y = finalTargetYRotation,
            z = currentState.currentRotation.z
        )

        val newTargetRotation = Rotation(
            x = currentState.currentRotation.x, // Keep current pitch
            y = finalTargetYRotation,
            z = currentState.currentRotation.z  // Keep current roll
        )
        _uiState.update {
            it.copy(
                targetRotation = newTargetRotation,
                currentRotation = newTargetRotation // Update logical current to match target immediately for next calc
            )
        }
        Log.d(TAG, "Setting target rotation to: $newTargetRotation")
    }

    // --- New: Helper to find closest offset ---
    private fun findClosestOffset(longitude: Float): Float? {
        // Use the complete map offsetCenterLongitudes
        return offsetCenterLongitudes.minByOrNull { (_, centerLon) ->
            var diff = abs(longitude - centerLon)
            if (diff > 180f) diff = 360f - diff // Handle wrap-around
            diff
        }?.key
    }

    fun updateRotation(newRotation: Rotation) {
        _uiState.update {
            it.copy(
                currentRotation = newRotation,
                targetRotation = null // Clear any previous animation target
            )
        }
    }

    fun resetRotation() {
        val zeroRotation = Rotation(0f, 0f, 0f)
        // Set both current and target to trigger potential animation/snap to zero
        _uiState.update {
            it.copy(
                currentRotation = zeroRotation,
                targetRotation = zeroRotation
            )
        }
    }

    // Helper methods that were missing
    private fun calculateAllTimeZoneOffsets() {
        timeZoneOffsets.clear()
        rawTimeZonePolygons.keys.forEach { tzId ->
            try {
                val zoneId = ZoneId.of(tzId)
                val now = ZonedDateTime.now(zoneId)
                val offsetSeconds = now.offset.totalSeconds
                timeZoneOffsets[tzId] = offsetSeconds
            } catch (e: Exception) {
                Log.w(TAG, "Could not calculate offset for timezone: $tzId", e)
            }
        }
    }

    private fun getFormattedOffset(timeZoneId: String?): String? {
        if (timeZoneId == null) return null
        try {
            val zoneId = ZoneId.of(timeZoneId)
            val now = ZonedDateTime.now(zoneId)
            val offset = now.offset
            val offsetHours = offset.totalSeconds / 3600
            val offsetMinutes = abs((offset.totalSeconds % 3600) / 60)
            return when {
                offset.totalSeconds == 0 -> "UTC"
                else -> "UTC${if (offsetHours >= 0) "+" else ""}${offsetHours}${if (offsetMinutes > 0) ":${"%02d".format(offsetMinutes)}" else ""}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting offset for $timeZoneId", e)
            return null
        }
    }

    // Missing property that was referenced
    private val hourlyOffsetCenterLongitudes = mutableMapOf<Int, Float>()
}