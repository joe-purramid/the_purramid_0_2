// ClockViewModel.kt
package com.example.purramid.thepurramid.clock.viewmodel

import android.graphics.Color
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.data.db.ClockDao
import com.example.purramid.thepurramid.data.db.ClockStateEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

// --- ClockState Data Class (keep as defined previously) ---
data class ClockState(
    val clockId: Int,
    val timeZoneId: ZoneId = ZoneId.systemDefault(),
    val isPaused: Boolean = false,
    val displaySeconds: Boolean = true,
    val is24Hour: Boolean = false,
    val clockColor: Int = Color.WHITE,
    val mode: String = "digital", // "digital" or "analog"
    val isNested: Boolean = false,
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1,
    val windowHeight: Int = -1,
    val currentTime: LocalTime = LocalTime.now(),
    val manuallySetTime: LocalTime? = null
)


@HiltViewModel
class ClockViewModel @Inject constructor(
    private val clockDao: ClockDao,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        const val KEY_CLOCK_ID = "clockId"
        private const val TAG = "ClockViewModel"
        private const val TICK_INTERVAL_MS = 100L
    }

    private val clockId: Int? = savedStateHandle[KEY_CLOCK_ID]
    private val defaultState = ClockState(clockId ?: -1)

    private val _uiState = MutableStateFlow(defaultState)
    val uiState: StateFlow<ClockState> = _uiState.asStateFlow()

    private var tickerJob: Job? = null

    init {
        Log.d(TAG, "Initializing ViewModel for clockId: $clockId")
        if (clockId != null && clockId != -1) {
            loadInitialState(clockId)
        } else {
            Log.e(TAG, "Invalid clockId ($clockId), ViewModel will use default state but not persist.")
            startTicker() // Start ticker even with default state
        }
    }

    private fun loadInitialState(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = clockDao.getById(id)
            withContext(Dispatchers.Main) {
                val initialState = if (entity != null) {
                    Log.d(TAG, "Loaded state from DB for clock $id")
                    mapEntityToState(entity)
                } else {
                    Log.w(TAG, "No saved state found for clock $id, initializing with defaults.")
                    ClockState(clockId = id, currentTime = LocalTime.now(ZoneId.systemDefault())).also {
                        // Save the initial default state to the DB immediately
                        saveState(it)
                    }
                }
                _uiState.value = initialState
                // Start ticker only if not paused according to loaded state
                if (!initialState.isPaused) {
                    startTicker()
                }
            }
        }
    }

    // --- Ticker Logic ---
    private fun startTicker() {
        if (tickerJob?.isActive == true) return // Don't start if already running
        tickerJob = viewModelScope.launch(Dispatchers.Main) {
            Log.d(TAG, "Ticker starting for clock $clockId...")
            while (isActive) {
                _uiState.update { currentState ->
                    // Ticker only runs if not paused, so we just update time
                    val timeToUpdate = currentState.manuallySetTime ?: currentState.currentTime // Use manual time if set, else last known time
                    // Increment the time by the tick interval
                    val newTime = timeToUpdate.plusNanos(TICK_INTERVAL_MS * 1_000_000)
                    currentState.copy(currentTime = newTime.truncatedTo(ChronoUnit.NANOS))
                }
                delay(TICK_INTERVAL_MS)
            }
            Log.d(TAG, "Ticker coroutine ended for clock $clockId")
        }
    }

    private fun stopTicker() {
        if (tickerJob?.isActive == true) {
            tickerJob?.cancel()
            Log.d(TAG, "Ticker stopped for clock $clockId")
        }
        tickerJob = null
    }

    // --- State Update Actions ---

    /**
     * Pauses or resumes the clock's timekeeping.
     * When resuming, the clock continues from the time it was paused at.
     */
    fun setPaused(shouldPause: Boolean) {
        if (_uiState.value.isPaused == shouldPause) return // No change needed

        _uiState.update { currentState ->
            // When pausing, keep the current time.
            // When resuming, keep the current time (don't reset to now).
            // Clear manually set time ONLY when resuming from a manually set state.
            val manualTime = if (!shouldPause && currentState.manuallySetTime != null) null else currentState.manuallySetTime
            currentState.copy(isPaused = shouldPause, manuallySetTime = manualTime)
        }

        if (shouldPause) {
            stopTicker()
        } else {
            startTicker() // Resuming starts the ticker from the current time
        }
        saveState(_uiState.value) // Save the paused state
    }


    /**
     * Resets the clock to the current actual time in its time zone,
     * clears any manually set time, and ensures it's running.
     */
    fun resetTime() {
        _uiState.update {
            it.copy(
                currentTime = LocalTime.now(it.timeZoneId), // Reset to actual current time
                isPaused = false, // Ensure not paused
                manuallySetTime = null // Clear manually set time
            )
        }
        startTicker() // Ensure ticker is running
        saveState(_uiState.value)
    }

    /**
     * Sets a specific time, usually from user interaction (dragging hands).
     * This action implies the clock should be paused.
     */
    fun setManuallySetTime(manualTime: LocalTime) {
        // Ensure clock is paused before accepting manual time
        if (!_uiState.value.isPaused) {
            Log.d(TAG, "Pausing clock $clockId before setting manual time.")
            stopTicker() // Stop ticker explicitly
        }
        _uiState.update {
            it.copy(
                manuallySetTime = manualTime,
                currentTime = manualTime, // Update display time immediately
                isPaused = true // Ensure it's marked as paused
            )
        }
        saveState(_uiState.value) // Save the manually set time and paused state
    }


    // --- Settings Updates (Remain the same, call saveState) ---
    fun updateMode(newMode: String) {
        if (_uiState.value.mode == newMode) return
        val resetTime = LocalTime.now(_uiState.value.timeZoneId)
        _uiState.update { it.copy(mode = newMode, currentTime = resetTime, manuallySetTime = null, isPaused = false) }
        startTicker()
        saveState(_uiState.value)
    }
    fun updateColor(newColor: Int) {
        if (_uiState.value.clockColor == newColor) return
        _uiState.update { it.copy(clockColor = newColor) }
        saveState(_uiState.value)
    }
    fun updateIs24Hour(is24: Boolean) {
        if (_uiState.value.is24Hour == is24) return
        _uiState.update { it.copy(is24Hour = is24) }
        saveState(_uiState.value)
    }
    fun updateTimeZone(zoneId: ZoneId) {
        if (_uiState.value.timeZoneId == zoneId) return
        val newTime = LocalTime.now(zoneId)
        _uiState.update { it.copy(timeZoneId = zoneId, currentTime = newTime, manuallySetTime = null) }
        saveState(_uiState.value)
    }
    fun updateDisplaySeconds(display: Boolean) {
        if (_uiState.value.displaySeconds == display) return
        _uiState.update { it.copy(displaySeconds = display) }
        saveState(_uiState.value)
    }
    fun updateIsNested(isNested: Boolean) {
        if (_uiState.value.isNested == isNested) return
        _uiState.update { it.copy(isNested = isNested) }
        saveState(_uiState.value)
    }
    fun updateWindowPosition(x: Int, y: Int) {
        if (_uiState.value.windowX == x && _uiState.value.windowY == y) return
        _uiState.update { it.copy(windowX = x, windowY = y) }
        saveState(_uiState.value)
    }
    fun updateWindowSize(width: Int, height: Int) {
        if (_uiState.value.windowWidth == width && _uiState.value.windowHeight == height) return
        _uiState.update { it.copy(windowWidth = width, windowHeight = height) }
        saveState(_uiState.value)
    }

    // --- Persistence (Remains the same) ---
    private fun saveState(state: ClockState) { /* ... */ }
    fun deleteState() { /* ... */ }

    // --- Mappers (Remain the same) ---
    private fun mapEntityToState(entity: ClockStateEntity): ClockState { /* ... */ }
    private fun mapStateToEntity(state: ClockState): ClockStateEntity { /* ... */ }

    // --- Cleanup (Remains the same) ---
    override fun onCleared() { /* ... */ }

    // --- Reimplementations of removed methods for clarity ---

    // Persistence
    private fun saveState(state: ClockState) {
        val currentId = state.clockId
        if (currentId == -1) {
            Log.w(TAG, "Cannot save state, invalid clockId: $currentId")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = mapStateToEntity(state)
                clockDao.insertOrUpdate(entity)
                Log.d(TAG, "Saved state for clock $currentId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save state for clock $currentId", e)
            }
        }
    }

    fun deleteState() {
        val currentId = clockId
        if (currentId == null || currentId == -1) {
            Log.w(TAG, "Cannot delete state, invalid clockId: $currentId")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                clockDao.deleteById(currentId)
                Log.d(TAG, "Deleted state for clock $currentId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete state for clock $currentId", e)
            }
        }
    }

    // Mappers
    private fun mapEntityToState(entity: ClockStateEntity): ClockState {
        val timeZone = try { ZoneId.of(entity.timeZoneId) } catch (e: Exception) { ZoneId.systemDefault() }
        val manualTime = entity.manuallySetTimeSeconds?.let { LocalTime.ofSecondOfDay(it % (24 * 3600)) }
        // If paused and has manual time, load that. Otherwise load current time for the zone.
        val initialCurrentTime = if (entity.isPaused && manualTime != null) manualTime else LocalTime.now(timeZone)

        return ClockState(
            clockId = entity.clockId,
            timeZoneId = timeZone,
            isPaused = entity.isPaused,
            displaySeconds = entity.displaySeconds,
            is24Hour = entity.is24Hour,
            clockColor = entity.clockColor,
            mode = entity.mode,
            isNested = entity.isNested,
            windowX = entity.windowX,
            windowY = entity.windowY,
            windowWidth = entity.windowWidth,
            windowHeight = entity.windowHeight,
            currentTime = initialCurrentTime.truncatedTo(ChronoUnit.NANOS), // Start with loaded/current time
            manuallySetTime = manualTime
        )
    }

    private fun mapStateToEntity(state: ClockState): ClockStateEntity {
        // Store the time that should persist when paused (either manually set or the time it was paused at)
        val timeToPersist = state.manuallySetTime ?: state.currentTime
        val timeToPersistSeconds = timeToPersist.toSecondOfDay().toLong()

        return ClockStateEntity(
            clockId = state.clockId,
            timeZoneId = state.timeZoneId.id,
            isPaused = state.isPaused,
            displaySeconds = state.displaySeconds,
            is24Hour = state.is24Hour,
            clockColor = state.clockColor,
            mode = state.mode,
            isNested = state.isNested,
            windowX = state.windowX,
            windowY = state.windowY,
            windowWidth = state.windowWidth,
            windowHeight = state.windowHeight,
            // Save the manual time if set, otherwise save the current displayed time IF PAUSED
            manuallySetTimeSeconds = if (state.isPaused) timeToPersistSeconds else null
        )
    }

    // Cleanup
    override fun onCleared() {
        Log.d(TAG, "ViewModel cleared for clockId: $clockId")
        stopTicker()
        super.onCleared()
    }

}
