// TimersViewModel.kt
package com.example.purramid.thepurramid.timers.viewmodel

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.data.db.TimerDao
import com.example.purramid.thepurramid.data.db.TimerStateEntity
import com.example.purramid.thepurramid.timers.TimerState
import com.example.purramid.thepurramid.timers.TimerType
import com.example.purramid.thepurramid.ui.PurramidPalette
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class TimersViewModel @Inject constructor(
    private val timerDao: TimerDao,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        const val KEY_TIMER_ID = "timerId"
        private const val TAG = "TimersViewModel"
        private const val TICK_INTERVAL_MS = 50L // Update interval for smoother display
    }

    // Get timerId passed via Intent/Args through SavedStateHandle
    private val timerId: Int = savedStateHandle[KEY_TIMER_ID] ?: 0 // Default to 0 or handle error

    private val _uiState = MutableStateFlow(TimerState(timerId = timerId))
    val uiState: StateFlow<TimerState> = _uiState.asStateFlow()

    private var tickerJob: Job? = null
    private val gson = Gson() // For laps persistence

    init {
        Log.d(TAG, "Initializing ViewModel for timerId: $timerId")
        if (timerId != 0) {
            loadInitialState(timerId)
        } else {
            Log.e(TAG, "Invalid timerId (0), using default state without persistence.")
            // Maybe generate a temporary ID if needed for non-persistent operation?
        }
    }

    private fun loadInitialState(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = timerDao.getById(id)
            withContext(Dispatchers.Main) {
                if (entity != null) {
                    Log.d(TAG, "Loaded state from DB for timer $id")
                    _uiState.value = mapEntityToState(entity)
                    // If it was running when saved, potentially resume ticking
                    if (_uiState.value.isRunning) {
                        startTicker()
                    }
                } else {
                    Log.d(TAG, "No saved state for timer $id, using defaults.")
                    val defaultState = TimerState(timerId = id) // Ensure ID is set
                    _uiState.value = defaultState
                    saveState(defaultState) // Save initial default state
                }
            }
        }
    }

    // --- Timer Controls ---

    fun togglePlayPause() {
        val currentState = _uiState.value
        if (currentState.type == TimerType.COUNTDOWN && currentState.currentMillis <= 0L) {
            return // Don't start countdown if already finished
        }

        val newRunningState = !currentState.isRunning
        _uiState.update { it.copy(isRunning = newRunningState) }

        if (newRunningState) {
            startTicker()
        } else {
            stopTicker()
        }
        saveState(_uiState.value)
    }

    fun resetTimer() {
        stopTicker()
        _uiState.update {
            it.copy(
                isRunning = false,
                currentMillis = if (it.type == TimerType.COUNTDOWN) it.initialDurationMillis else 0L,
                laps = emptyList()
            )
        }
        saveState(_uiState.value)
    }

    fun addLap() {
        val currentState = _uiState.value
        if (currentState.type != TimerType.STOPWATCH || !currentState.isRunning) return

        val currentLaps = currentState.laps.toMutableList()
        currentLaps.add(currentState.currentMillis) // Add current elapsed time as lap
        _uiState.update { it.copy(laps = currentLaps) }
        saveState(_uiState.value)
    }

    // --- Ticker Logic ---

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        val startTime = SystemClock.elapsedRealtime()
        val initialMillis = _uiState.value.currentMillis

        tickerJob = viewModelScope.launch(Dispatchers.Default) { // Use Default for timing
            while (isActive && _uiState.value.isRunning) {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                val newMillis = when (_uiState.value.type) {
                    TimerType.STOPWATCH -> initialMillis + elapsed
                    TimerType.COUNTDOWN -> (initialMillis - elapsed).coerceAtLeast(0L)
                }

                withContext(Dispatchers.Main.immediate) { // Update UI state on Main
                     _uiState.update { it.copy(currentMillis = newMillis) }
                }


                if (_uiState.value.type == TimerType.COUNTDOWN && newMillis <= 0L) {
                    handleCountdownFinish()
                    break // Stop ticker
                }

                delay(TICK_INTERVAL_MS)
            }
            Log.d(TAG, "Ticker coroutine ended for timer $timerId")
        }
         Log.d(TAG, "Ticker starting for timer $timerId...")
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        Log.d(TAG, "Ticker stopped for timer $timerId")
    }

    private fun handleCountdownFinish() {
        stopTicker()
        // Update state on main thread
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update { it.copy(isRunning = false, currentMillis = 0) }
            if (_uiState.value.playSoundOnEnd) {
                // TODO: Implement sound playing logic (e.g., via service or activity)
                Log.d(TAG, "Timer $timerId finished - Play Sound!")
            } else {
                 Log.d(TAG, "Timer $timerId finished.")
            }
            saveState(_uiState.value) // Save finished state
        }
    }

     // --- Settings Updates --- TODO: Implement fully in Settings screen later
     fun setInitialDuration(durationMillis: Long) {
         if (_uiState.value.type == TimerType.COUNTDOWN && !_uiState.value.isRunning) {
            _uiState.update { it.copy(initialDurationMillis = durationMillis, currentMillis = durationMillis) }
             saveState(_uiState.value)
         }
     }

     fun setShowCentiseconds(show: Boolean) {
        if (_uiState.value.showCentiseconds == show) return
         _uiState.update { it.copy(showCentiseconds = show) }
         saveState(_uiState.value)
     }

     fun setPlaySoundOnEnd(play: Boolean) {
        if (_uiState.value.playSoundOnEnd == play) return
         _uiState.update { it.copy(playSoundOnEnd = play) }
         saveState(_uiState.value)
     }

     fun setTimerType(type: TimerType) {
        if (_uiState.value.type == type) return
         stopTicker() // Stop ticker when changing type
         _uiState.update {
             it.copy(
                 type = type,
                 isRunning = false,
                 // Reset time based on new type
                 currentMillis = if (type == TimerType.COUNTDOWN) it.initialDurationMillis else 0L,
                 laps = emptyList() // Clear laps when switching
             )
         }
         saveState(_uiState.value)
     }

    fun updateOverlayColor(newColor: Int) {
        if (_uiState.value.overlayColor == newColor) return
        _uiState.update { it.copy(overlayColor = newColor) }
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


    // --- Persistence ---
    private fun saveState(state: TimerState) {
        if (state.timerId <= 0) {
            Log.w(TAG, "Attempted to save state with invalid timerId: ${state.timerId}")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = mapStateToEntity(state)
                timerDao.insertOrUpdate(entity)
                Log.d(TAG, "Saved state for timer ${state.timerId}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save state for timer ${state.timerId}", e)
            }
        }
    }

     fun deleteState() {
         if (timerId <= 0) return
         stopTicker()
         viewModelScope.launch(Dispatchers.IO) {
             try {
                 timerDao.deleteById(timerId)
                 Log.d(TAG, "Deleted state for timer $timerId")
             } catch (e: Exception) {
                 Log.e(TAG, "Failed to delete state for timer $timerId", e)
             }
         }
     }


    // --- Mappers ---
    private fun mapEntityToState(entity: TimerStateEntity): TimerState {
        val lapsList = try {
            val typeToken = object : TypeToken<List<Long>>() {}.type
            gson.fromJson<List<Long>>(entity.lapsJson, typeToken) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse laps JSON, using empty list.", e)
            emptyList()
        }

        return TimerState(
            timerId = entity.timerId,
            type = try { TimerType.valueOf(entity.type) } catch (e: Exception) { TimerType.STOPWATCH },
            initialDurationMillis = entity.initialDurationMillis,
            // Load currentMillis - if it was running, this might be stale, ticker should adjust
            currentMillis = entity.currentMillis,
            isRunning = entity.isRunning, // Reflect if it was running when saved
            laps = lapsList,
            showCentiseconds = entity.showCentiseconds,
            playSoundOnEnd = entity.playSoundOnEnd,
            overlayColor = entity.overlayColor,
            windowX = entity.windowX,
            windowY = entity.windowY,
            windowWidth = entity.windowWidth,
            windowHeight = entity.windowHeight
        )
    }

    private fun mapStateToEntity(state: TimerState): TimerStateEntity {
        val lapsJson = gson.toJson(state.laps)
        return TimerStateEntity(
            timerId = state.timerId,
            type = state.type.name,
            initialDurationMillis = state.initialDurationMillis,
            currentMillis = state.currentMillis,
            isRunning = state.isRunning,
            lapsJson = lapsJson,
            showCentiseconds = state.showCentiseconds,
            playSoundOnEnd = state.playSoundOnEnd,
            overlayColor = state.overlayColor,
            windowX = state.windowX,
            windowY = state.windowY,
            windowWidth = state.windowWidth,
            windowHeight = state.windowHeight
        )
    }

    // --- Cleanup ---
    override fun onCleared() {
        Log.d(TAG, "ViewModel cleared for timerId: $timerId")
        stopTicker()
        // Save final state? Usually done on change, but consider edge cases.
        // if (timerId > 0) saveState(_uiState.value)
        super.onCleared()
    }
}