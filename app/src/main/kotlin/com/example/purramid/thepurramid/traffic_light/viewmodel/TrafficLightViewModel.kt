package com.example.purramid.thepurramid.traffic_light.viewmodel

import android.os.SystemClock // For double-tap timing
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
// import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.data.db.TrafficLightDao
import com.example.purramid.thepurramid.data.db.TrafficLightStateEntity
import com.example.purramid.thepurramid.traffic_light.AdjustValuesFragment // For ColorForRange enum
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class TrafficLightViewModel @Inject constructor(
    private val trafficLightDao: TrafficLightDao, // Inject DAO
    private val savedStateHandle: SavedStateHandle // Inject SavedStateHandle
) : ViewModel() {

    companion object {
        const val KEY_INSTANCE_ID = TrafficLightActivity.EXTRA_INSTANCE_ID // Use key from Activity
        private const val TAG = "TrafficLightVM"
    }

    // instanceId passed via Intent/Args through SavedStateHandle
    private val instanceId: Int? = savedStateHandle[KEY_INSTANCE_ID]

    private val _uiState = MutableStateFlow(TrafficLightState())
    val uiState: StateFlow<TrafficLightState> = _uiState.asStateFlow()

    // Variables for double-tap detection
    private var lastTapTimeMs: Long = 0
    private var lastTappedColor: LightColor? = null
    private val doubleTapTimeoutMs: Long = 500 // Standard double-tap timeout

    init {
        Log.d(TAG, "Initializing ViewModel for instanceId: $instanceId")
        if (instanceId != null) {
            loadInitialState(instanceId)
        } else {
            Log.e(TAG, "Instance ID is null, cannot load state.")
            // Consider setting an error state or using a default non-persistent instance ID
            _uiState.update { it.copy(instanceId = -1) } // Indicate error or default
        }
    }

    private fun loadInitialState(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = trafficLightDao.getById(id)
            withContext(Dispatchers.Main) {
                if (entity != null) {
                    Log.d(TAG, "Loaded state from DB for instance $id")
                    _uiState.value = mapEntityToState(entity)
                } else {
                    Log.d(TAG, "No saved state found for instance $id, using defaults.")
                    // Initialize with default state for this new instance ID
                    val defaultState = TrafficLightState(instanceId = id)
                    _uiState.value = defaultState
                    // Save the initial default state to the DB
                    saveState(defaultState)
                }
            }
        }
    }

    fun handleLightTap(tappedColor: LightColor) {
        val currentTimeMs = SystemClock.elapsedRealtime()
        val currentState = _uiState.value

        if (currentState.currentMode != TrafficLightMode.MANUAL_CHANGE) return

        var newActiveLight: LightColor? = null
        if (currentState.activeLight == tappedColor &&
            (currentTimeMs - lastTapTimeMs) < doubleTapTimeoutMs &&
            lastTappedColor == tappedColor
        ) {
            // Double tap on active: turn off
            newActiveLight = null
            lastTapTimeMs = 0
            lastTappedColor = null
        } else {
            // Single tap or different light: turn tapped on
            newActiveLight = tappedColor
            lastTapTimeMs = currentTimeMs
            lastTappedColor = tappedColor
        }

        if (currentState.activeLight != newActiveLight) {
            _uiState.update { it.copy(activeLight = newActiveLight) }
            saveState(_uiState.value) // Save updated state
        }
    }

    fun setOrientation(newOrientation: Orientation) {
        if (_uiState.value.orientation == newOrientation) return
        _uiState.update { it.copy(orientation = newOrientation) }
        saveState(_uiState.value)
    }

    fun setMode(newMode: TrafficLightMode) {
        val currentState = _uiState.value
        if (currentState.currentMode == newMode) return

        var updatedActiveLight = currentState.activeLight
        if (currentState.activeLight != null) {
            updatedActiveLight = null // Clear light when changing mode
        }

        _uiState.update { it.copy(currentMode = newMode, activeLight = updatedActiveLight) }
        saveState(_uiState.value)
    }

    fun toggleBlinking(isEnabled: Boolean) {
        if (_uiState.value.isBlinkingEnabled == isEnabled) return
        _uiState.update { it.copy(isBlinkingEnabled = isEnabled) }
        saveState(_uiState.value)
    }

    fun setSettingsOpen(isOpen: Boolean) {
        // isSettingsOpen is likely transient UI state, maybe don't persist?
        // If persistence is desired, uncomment saveState.
        _uiState.update { it.copy(isSettingsOpen = isOpen) }
        // saveState(_uiState.value)
    }

    // --- Placeholder functions for settings items to be implemented later ---
    fun setShowTimeRemaining(show: Boolean) {
        if (_uiState.value.showTimeRemaining == show) return
        _uiState.update { it.copy(showTimeRemaining = show) }
        saveState(_uiState.value) // Save the change
   }

    fun setShowTimeline(show: Boolean) {
        if (_uiState.value.showTimeline == show) return
        _uiState.update { it.copy(showTimeline = show) }
        saveState(_uiState.value) // Save the change
        }

    fun updateResponsiveSettings(newSettings: ResponsiveModeSettings) {
        if (_uiState.value.responsiveModeSettings == newSettings) return
        _uiState.update { it.copy(responsiveModeSettings = newSettings) }
        saveState(_uiState.value)
    }

    fun setDangerousSoundAlert(isEnabled: Boolean) {
        val currentSettings = _uiState.value.responsiveModeSettings
        if (currentSettings.dangerousSoundAlertEnabled == isEnabled) return
        val newSettings = currentSettings.copy(dangerousSoundAlertEnabled = isEnabled)
        updateResponsiveSettings(newSettings) // Calls saveState internally
    }

    fun updateSpecificDbValue(
        colorForRange: AdjustValuesFragment.ColorForRange,
        isMinField: Boolean,
        newValue: Int?
    ) {
        val currentSettings = _uiState.value.responsiveModeSettings
        val updatedSettings = calculateUpdatedRanges(currentSettings, colorForRange, isMinField, newValue)

        if (currentSettings != updatedSettings) {
            updateResponsiveSettings(updatedSettings) // Calls saveState internally
        }
    }

    // Extracted calculation logic (remains complex, needs careful implementation)
    private fun calculateUpdatedRanges(
        currentSettings: ResponsiveModeSettings,
        colorForRange: AdjustValuesFragment.ColorForRange,
        isMinField: Boolean,
        newValue: Int?
    ) : ResponsiveModeSettings {
        // --- START OF COMPLEX LINKED LOGIC (placeholder - needs full implementation) ---
        var newGreen = currentSettings.greenRange
        var newYellow = currentSettings.yellowRange
        var newRed = currentSettings.redRange
        val safeValue = newValue?.coerceIn(0, 120)

        // TODO: Implement the full cascading logic for min/max and N/A states
        // This placeholder just updates the specific field without validation/cascading
        when(colorForRange) {
            AdjustValuesFragment.ColorForRange.GREEN -> newGreen = if (isMinField) newGreen.copy(minDb = safeValue) else newGreen.copy(maxDb = safeValue)
            AdjustValuesFragment.ColorForRange.YELLOW -> newYellow = if (isMinField) newYellow.copy(minDb = safeValue) else newYellow.copy(maxDb = safeValue)
            AdjustValuesFragment.ColorForRange.RED -> newRed = if (isMinField) newRed.copy(minDb = safeValue) else newRed.copy(maxDb = safeValue)
        }
        // --- END OF COMPLEX LINKED LOGIC ---

        // Return potentially modified ranges
        return currentSettings.copy(greenRange = newGreen, yellowRange = newYellow, redRange = newRed)
    }

    // --- Persistence ---
    private fun saveState(state: TrafficLightState) {
        val currentInstanceId = state.instanceId
        if (currentInstanceId <= 0) { // Don't save if ID is invalid/default
            Log.w(TAG, "Attempted to save state with invalid instanceId: $currentInstanceId")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = mapStateToEntity(state)
                trafficLightDao.insertOrUpdate(entity)
                Log.d(TAG, "Saved state for instance $currentInstanceId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save state for instance $currentInstanceId", e)
                // Optionally notify UI of save failure
            }
        }
    }

    fun saveWindowPosition(x: Int, y: Int) {
        val currentState = _uiState.value
        if (currentState.windowX == x && currentState.windowY == y) return
        _uiState.update { it.copy(windowX = x, windowY = y) }
        saveState(_uiState.value)
    }

    fun saveWindowSize(width: Int, height: Int) {
        val currentState = _uiState.value
        if (currentState.windowWidth == width && currentState.windowHeight == height) return
        _uiState.update { it.copy(windowWidth = width, windowHeight = height) }
        saveState(_uiState.value)
    }

    // --- Mappers ---
    private fun mapEntityToState(entity: TrafficLightStateEntity): TrafficLightState {
        val responsiveSettings = try {
            gson.fromJson(entity.responsiveModeSettingsJson, ResponsiveModeSettings::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ResponsiveModeSettings JSON, using default.", e)
            ResponsiveModeSettings() // Default on error
        }
        return TrafficLightState(
            instanceId = entity.instanceId,
            currentMode = try { TrafficLightMode.valueOf(entity.currentMode) } catch (e: Exception) { TrafficLightMode.MANUAL_CHANGE },
            orientation = try { Orientation.valueOf(entity.orientation) } catch (e: Exception) { Orientation.VERTICAL },
            isBlinkingEnabled = entity.isBlinkingEnabled,
            activeLight = entity.activeLight?.let { try { LightColor.valueOf(it) } catch (e: Exception) { null } },
            isSettingsOpen = entity.isSettingsOpen,
            isMicrophoneAvailable = entity.isMicrophoneAvailable,
            numberOfOpenInstances = entity.numberOfOpenInstances,
            responsiveModeSettings = responsiveSettings,
            windowX = entity.windowX,
            windowY = entity.windowY,
            windowWidth = entity.windowWidth,
            windowHeight = entity.windowHeight
        )
    }

    private fun mapStateToEntity(state: TrafficLightState): TrafficLightStateEntity {
        val responsiveJson = gson.toJson(state.responsiveModeSettings)
        return TrafficLightStateEntity(
            instanceId = state.instanceId,
            currentMode = state.currentMode.name,
            orientation = state.orientation.name,
            isBlinkingEnabled = state.isBlinkingEnabled,
            activeLight = state.activeLight?.name,
            isSettingsOpen = state.isSettingsOpen,
            isMicrophoneAvailable = state.isMicrophoneAvailable,
            numberOfOpenInstances = state.numberOfOpenInstances, // This might be better managed globally
            responsiveModeSettingsJson = responsiveJson,
            showTimeRemaining = state.showTimeRemaining,
            showTimeline = state.showTimeline,
            windowX = state.windowX,
            windowY = state.windowY,
            windowWidth = state.windowWidth,
            windowHeight = state.windowHeight
        )
    }

    // --- Cleanup ---
    override fun onCleared() {
        Log.d(TAG, "ViewModel cleared for instanceId: $instanceId")
        // Persist final state before clearing? Usually done on state change.
        // saveState(_uiState.value) // Consider if needed here
        super.onCleared()
    }
}