// ScreenMaskViewModel.kt
package com.example.purramid.thepurramid.screen_mask.viewmodel

import android.graphics.Color
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.data.db.ScreenMaskDao
import com.example.purramid.thepurramid.data.db.ScreenMaskStateEntity
import com.example.purramid.thepurramid.screen_mask.ScreenMaskState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ScreenMaskViewModel @Inject constructor(
    private val screenMaskDao: ScreenMaskDao,
    private val savedStateHandle: SavedStateHandle // Hilt injects this
) : ViewModel() {

    companion object {
        const val KEY_INSTANCE_ID = "screenMaskInstanceId"
        private const val TAG = "ScreenMaskViewModel"
    }

    private val instanceId: Int = 0

    fun initialize(id: Int) {
        if (instanceId != 0) {
            Log.w(TAG, "ViewModel already initialized with ID $instanceId, ignoring new ID $id")
            return
        }

        instanceId = id
        savedStateHandle[KEY_INSTANCE_ID] = id

        Log.d(TAG, "Initializing ViewModel for instanceId: $instanceId")
        if (instanceId != 0) {
            loadState()
        }
    }

    private val _uiState = MutableStateFlow(ScreenMaskState(instanceId = instanceId))
    val uiState: StateFlow<ScreenMaskState> = _uiState.asStateFlow()

    private fun loadState() {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = screenMaskDao.getById(instanceId)
            withContext(Dispatchers.Main) {
                if (entity != null) {
                    _uiState.value = mapEntityToState(entity)
                    Log.d(TAG, "Loaded state for instance $instanceId: ${uiState.value}")
                } else {
                    // If no state in DB, this is likely a new instance.
                    // The service would have generated an ID. We save the initial default state.
                    Log.d(TAG, "No state in DB for instance $instanceId. Saving initial default state.")
                    saveState(_uiState.value) // Save current (default) state
                }
            }
        }
    }

    fun updatePosition(x: Int, y: Int) {
        if (_uiState.value.x == x && _uiState.value.y == y) return
        _uiState.update { it.copy(x = x, y = y) }
        saveState(_uiState.value)
    }

    fun updateSize(width: Int, height: Int) {
        if (_uiState.value.width == width && _uiState.value.height == height) return
        _uiState.update { it.copy(width = width, height = height) }
        saveState(_uiState.value)
    }

    fun toggleLock() {
        _uiState.update { it.copy(isLocked = !it.isLocked) }
        saveState(_uiState.value)
    }

    fun setLocked(locked: Boolean, isFromLockAll: Boolean = false) {
        _uiState.update { it.copy(isLocked = locked) }
        saveState(_uiState.value)
    }

    fun isLocked(): Boolean = _uiState.value.isLocked

    fun setBillboardImageUri(uriString: String?) {
        if (_uiState.value.billboardImageUri == uriString) return
        _uiState.update { it.copy(billboardImageUri = uriString, isBillboardVisible = uriString != null) }
        saveState(_uiState.value)
    }

    fun toggleBillboardVisibility() {
        if (_uiState.value.billboardImageUri == null && !_uiState.value.isBillboardVisible) return // Can't make visible if no URI
        _uiState.update { it.copy(isBillboardVisible = !it.isBillboardVisible) }
        saveState(_uiState.value)
    }

    fun toggleControlsVisibility() {
        _uiState.update { it.copy(isControlsVisible = !it.isControlsVisible) }
        saveState(_uiState.value)
    }

    private fun saveState(state: ScreenMaskState) {
        if (state.instanceId <= 0) {
            Log.w(TAG, "Attempted to save state with invalid instanceId: ${state.instanceId}")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                screenMaskDao.insertOrUpdate(mapStateToEntity(state))
                Log.d(TAG, "Saved state for instance ${state.instanceId}")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving state for instance ${state.instanceId}", e)
                // Consider emitting an error event to UI
            }
        }
    }

    fun deleteState() {
        if (instanceId <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                screenMaskDao.deleteById(instanceId)
                Log.d(TAG, "Deleted state for instance $instanceId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting state for instance $instanceId", e)
            }
        }
    }

    private fun mapEntityToState(entity: ScreenMaskStateEntity): ScreenMaskState {
        return ScreenMaskState(
            instanceId = entity.instanceId,
            x = entity.x,
            y = entity.y,
            width = entity.width,
            height = entity.height,
            isLocked = entity.isLocked,
            billboardImageUri = entity.billboardImageUri,
            isBillboardVisible = entity.isBillboardVisible,
            isControlsVisible = entity.isControlsVisible
        )
    }

    private fun mapStateToEntity(state: ScreenMaskState): ScreenMaskStateEntity {
        return ScreenMaskStateEntity(
            instanceId = state.instanceId,
            x = state.x,
            y = state.y,
            width = state.width,
            height = state.height,
            isLocked = state.isLocked,
            billboardImageUri = state.billboardImageUri,
            isBillboardVisible = state.isBillboardVisible,
            isControlsVisible = state.isControlsVisible
        )
    }

    override fun onCleared() {
        Log.d(TAG, "ViewModel cleared for instanceId: $instanceId")
        super.onCleared()
    }
}