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
import java.util.UUID
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
        private const val TICK_INTERVAL_MS = 50L
        private const val MAX_LAPS = 10 // As per specification
    }

    // Get timerId passed via SavedStateHandle
    private val timerId: Int = savedStateHandle[KEY_TIMER_ID] ?: 0

    private val _uiState = MutableStateFlow(TimerState(timerId = timerId))
    val uiState: StateFlow<TimerState> = _uiState.asStateFlow()

    private var tickerJob: Job? = null
    private val gson = Gson()

    init {
        Log.d(TAG, "Initializing ViewModel for timerId: $timerId")
        if (timerId != 0) {
            loadInitialState(timerId)
        } else {
            Log.e(TAG, "Invalid timerId (0), using default state without persistence.")
        }
    }

    private fun loadInitialState(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = timerDao.getById(id)
                withContext(Dispatchers.Main) {
                    if (entity != null) {
                        Log.d(TAG, "Loaded state from DB for timer $id")
                        _uiState.value = mapEntityToState(entity)
                        if (_uiState.value.isRunning) {
                            startTicker()
                        }
                    } else {
                        Log.d(TAG, "No saved state for timer $id, using defaults.")
                        val defaultState = TimerState(
                            timerId = id,
                            uuid = UUID.randomUUID() // Generate new UUID
                        )
                        _uiState.value = defaultState
                        saveState(defaultState)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial state for timer $id", e)
                withContext(Dispatchers.Main) {
                    val defaultState = TimerState(
                        timerId = id,
                        uuid = UUID.randomUUID()
                    )
                    _uiState.value = defaultState
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

        // Check max laps limit from specification
        if (currentState.laps.size >= MAX_LAPS) {
            Log.d(TAG, "Maximum number of laps ($MAX_LAPS) reached")
            return
        }

        val currentLaps = currentState.laps.toMutableList()
        currentLaps.add(currentState.currentMillis)
        _uiState.update { it.copy(laps = currentLaps) }
        saveState(_uiState.value)
    }

    // --- Ticker Logic ---

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        val startTime = SystemClock.elapsedRealtime()
        val initialMillis = _uiState.value.currentMillis

        tickerJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && _uiState.value.isRunning) {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                val newMillis = when (_uiState.value.type) {
                    TimerType.STOPWATCH -> initialMillis + elapsed
                    TimerType.COUNTDOWN -> (initialMillis - elapsed).coerceAtLeast(0L)
                }

                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { it.copy(currentMillis = newMillis) }
                }

                if (_uiState.value.type == TimerType.COUNTDOWN && newMillis <= 0L) {
                    handleCountdownFinish()
                    break
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
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update { it.copy(isRunning = false, currentMillis = 0) }
            if (_uiState.value.playSoundOnEnd) {
                playFinishSound()
            }
            saveState(_uiState.value)
        }
    }

    private fun playFinishSound() {
        try {
            // Use saved sound URI if available, otherwise use default
            val soundUri = _uiState.value.selectedSoundUri
            if (soundUri != null) {
                // TODO: Implement playing custom sound URI
                Log.d(TAG, "Timer $timerId finished - Play custom sound: $soundUri")
            } else if (_uiState.value.musicUrl != null) {
                // TODO: Implement playing music URL
                Log.d(TAG, "Timer $timerId finished - Play music URL: ${_uiState.value.musicUrl}")
            } else {
                // Play default notification sound
                Log.d(TAG, "Timer $timerId finished - Play default sound")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing finish sound", e)
        }
    }

    // --- Settings Updates ---
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
        stopTicker()
        _uiState.update {
            it.copy(
                type = type,
                isRunning = false,
                currentMillis = if (type == TimerType.COUNTDOWN) it.initialDurationMillis else 0L,
                laps = emptyList()
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

    // --- New Feature Methods ---
    fun setNested(nested: Boolean) {
        if (_uiState.value.isNested == nested) return
        _uiState.update {
            it.copy(
                isNested = nested,
                // Reset nested position when toggling off
                nestedX = if (nested) it.nestedX else -1,
                nestedY = if (nested) it.nestedY else -1
            )
        }
        saveState(_uiState.value)
    }

    fun updateNestedPosition(x: Int, y: Int) {
        if (_uiState.value.nestedX == x && _uiState.value.nestedY == y) return
        _uiState.update { it.copy(nestedX = x, nestedY = y) }
        saveState(_uiState.value)
    }

    fun setSoundsEnabled(enabled: Boolean) {
        if (_uiState.value.soundsEnabled == enabled) return
        _uiState.update { it.copy(soundsEnabled = enabled) }
        saveState(_uiState.value)
    }

    fun setShowLapTimes(show: Boolean) {
        if (_uiState.value.showLapTimes == show) return
        _uiState.update { it.copy(showLapTimes = show) }
        saveState(_uiState.value)
    }

    fun setSelectedSound(uri: String?) {
        if (_uiState.value.selectedSoundUri == uri) return
        _uiState.update { it.copy(selectedSoundUri = uri) }
        saveState(_uiState.value)
    }

    fun setMusicUrl(url: String?) {
        if (_uiState.value.musicUrl == url) return

        // Update recent URLs list
        val recentUrls = _uiState.value.recentMusicUrls.toMutableList()
        url?.let {
            recentUrls.remove(it) // Remove if already exists
            recentUrls.add(0, it) // Add to beginning
            if (recentUrls.size > 3) {
                recentUrls.removeAt(3) // Keep only last 3
            }
        }

        _uiState.update {
            it.copy(
                musicUrl = url,
                recentMusicUrls = recentUrls
            )
        }
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

        val recentUrlsList = try {
            val typeToken = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(entity.recentMusicUrlsJson, typeToken) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse recent URLs JSON, using empty list.", e)
            emptyList()
        }

        return TimerState(
            timerId = entity.timerId,
            uuid = try {
                UUID.fromString(entity.uuid)
            } catch (e: Exception) {
                Log.e(TAG, "Invalid UUID in entity, generating new one", e)
                UUID.randomUUID()
            },
            type = try {
                TimerType.valueOf(entity.type)
            } catch (e: Exception) {
                TimerType.STOPWATCH
            },
            initialDurationMillis = entity.initialDurationMillis,
            currentMillis = entity.currentMillis,
            isRunning = entity.isRunning,
            laps = lapsList,
            showCentiseconds = entity.showCentiseconds,
            playSoundOnEnd = entity.playSoundOnEnd,
            overlayColor = entity.overlayColor,
            windowX = entity.windowX,
            windowY = entity.windowY,
            windowWidth = entity.windowWidth,
            windowHeight = entity.windowHeight,
            isNested = entity.isNested,
            nestedX = entity.nestedX,
            nestedY = entity.nestedY,
            soundsEnabled = entity.soundsEnabled,
            selectedSoundUri = entity.selectedSoundUri,
            musicUrl = entity.musicUrl,
            recentMusicUrls = recentUrlsList,
            showLapTimes = entity.showLapTimes
        )
    }

    private fun mapStateToEntity(state: TimerState): TimerStateEntity {
        val lapsJson = gson.toJson(state.laps)
        val recentUrlsJson = gson.toJson(state.recentMusicUrls)

        return TimerStateEntity(
            timerId = state.timerId,
            uuid = state.uuid.toString(),
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
            windowHeight = state.windowHeight,
            isNested = state.isNested,
            nestedX = state.nestedX,
            nestedY = state.nestedY,
            soundsEnabled = state.soundsEnabled,
            selectedSoundUri = state.selectedSoundUri,
            musicUrl = state.musicUrl,
            recentMusicUrlsJson = recentUrlsJson,
            showLapTimes = state.showLapTimes
        )
    }

    // --- Cleanup ---
    override fun onCleared() {
        Log.d(TAG, "ViewModel cleared for timerId: $timerId")
        stopTicker()
        super.onCleared()
    }
}