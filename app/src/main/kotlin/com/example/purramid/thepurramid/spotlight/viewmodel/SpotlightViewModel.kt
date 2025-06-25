// SpotlightViewModel.kt
package com.example.purramid.thepurramid.spotlight.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.data.db.SpotlightDao
import com.example.purramid.thepurramid.data.db.SpotlightInstanceEntity
import com.example.purramid.thepurramid.data.db.SpotlightOpeningEntity
import com.example.purramid.thepurramid.spotlight.SpotlightUiState
import com.example.purramid.thepurramid.spotlight.SpotlightOpening
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.maxOf

@HiltViewModel
class SpotlightViewModel @Inject constructor(
    private val spotlightDao: SpotlightDao,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "SpotlightViewModel"
        const val KEY_INSTANCE_ID = "spotlight_instance_id"
        private const val DEFAULT_RADIUS = 125f // 250px diameter as per spec
    }

    private var instanceId: Int = 1 // Default, will be set via initialize()

    private val _uiState = MutableStateFlow(SpotlightUiState(instanceId = instanceId, isLoading = true))
    val uiState: StateFlow<SpotlightUiState> = _uiState.asStateFlow()

    private var isLockAllActive = false
    private val individuallyLockedOpenings = mutableSetOf<Int>()

    // Call this immediately after creation to set the instance ID
    fun initialize(instanceId: Int, screenWidth: Int, screenHeight: Int) {
        this.instanceId = instanceId
        _uiState.update { it.copy(instanceId = instanceId) }

        // Store screen dimensions for later use
        savedStateHandle["screen_width"] = screenWidth
        savedStateHandle["screen_height"] = screenHeight

        initializeInstance()
        observeOpenings()
    }

    private fun initializeInstance() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Check if instance exists
                var instance = spotlightDao.getInstanceById(instanceId)
                if (instance == null) {
                    // Create new instance
                    instance = SpotlightInstanceEntity(
                        instanceId = instanceId,
                        isActive = true
                    )
                    spotlightDao.insertOrUpdateInstance(instance)
                    Log.d(TAG, "Created new Spotlight instance: $instanceId")
                }

                // Load existing openings
                val openings = spotlightDao.getOpeningsForInstance(instanceId)
                if (openings.isEmpty()) {
                    // Create default opening at center
                    createDefaultOpening()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing instance", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, error = "Failed to initialize") }
                }
            }
        }
    }

    private fun observeOpenings() {
        viewModelScope.launch {
            spotlightDao.getOpeningsForInstanceFlow(instanceId).collect { openingEntities ->
                val openings = openingEntities.map { mapEntityToOpening(it) }
                _uiState.update { currentState ->
                    currentState.copy(
                        openings = openings,
                        isAnyLocked = openings.any { it.isLocked },
                        areAllLocked = openings.isNotEmpty() && openings.all { it.isLocked },
                        canAddMore = openings.size < SpotlightUiState.MAX_OPENINGS,
                        isLoading = false,
                        error = null
                    )
                }
            }
        }
    }

    fun addOpening(screenWidth: Int, screenHeight: Int) {
        if (_uiState.value.openings.size >= SpotlightUiState.MAX_OPENINGS) {
            Log.w(TAG, "Max openings reached")
            _uiState.update { it.copy(error = "Maximum number of spotlight openings reached") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existingCount = _uiState.value.openings.size
                // Offset new openings to avoid exact overlap
                val offsetX = 50f * existingCount
                val offsetY = 50f * existingCount

                val centerX = (screenWidth / 2f + offsetX).coerceIn(DEFAULT_RADIUS, screenWidth - DEFAULT_RADIUS)
                val centerY = (screenHeight / 2f + offsetY).coerceIn(DEFAULT_RADIUS, screenHeight - DEFAULT_RADIUS)

                val newOpening = SpotlightOpeningEntity(
                    instanceId = instanceId,
                    centerX = centerX,
                    centerY = centerY,
                    radius = DEFAULT_RADIUS,
                    width = DEFAULT_RADIUS * 2,
                    height = DEFAULT_RADIUS * 2,
                    size = DEFAULT_RADIUS * 2,
                    shape = SpotlightOpening.Shape.CIRCLE.name,
                    isLocked = false,
                    displayOrder = existingCount
                )

                spotlightDao.insertOpening(newOpening)
                Log.d(TAG, "Added new opening to instance $instanceId")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding opening", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(error = "Failed to add opening") }
                }
            }
        }
    }

    private suspend fun createDefaultOpening() {
        // Get screen dimensions from SavedStateHandle if available, otherwise use defaults
        val screenWidth = savedStateHandle.get<Int>("screen_width") ?: 1920
        val screenHeight = savedStateHandle.get<Int>("screen_height") ?: 1080

        val defaultOpening = SpotlightOpeningEntity(
            instanceId = instanceId,
            centerX = screenWidth / 2f,
            centerY = screenHeight / 2f,
            radius = DEFAULT_RADIUS,
            width = DEFAULT_RADIUS * 2,
            height = DEFAULT_RADIUS * 2,
            size = DEFAULT_RADIUS * 2,
            shape = SpotlightOpening.Shape.CIRCLE.name,
            isLocked = false,
            displayOrder = 0
        )

        spotlightDao.insertOpening(defaultOpening)
    }

    fun updateOpeningPosition(openingId: Int, newX: Float, newY: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val opening = spotlightDao.getOpeningById(openingId)
                if (opening != null && !opening.isLocked) {
                    val updated = opening.copy(centerX = newX, centerY = newY)
                    spotlightDao.updateOpening(updated)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating opening position", e)
            }
        }
    }

    fun toggleOpeningShape(openingId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val opening = spotlightDao.getOpeningById(openingId)
                if (opening != null && !opening.isLocked) {
                    val currentShape = SpotlightOpening.Shape.valueOf(opening.shape)
                    val newShape = when (currentShape) {
                        SpotlightOpening.Shape.CIRCLE -> SpotlightOpening.Shape.SQUARE
                        SpotlightOpening.Shape.SQUARE -> SpotlightOpening.Shape.CIRCLE
                        SpotlightOpening.Shape.OVAL -> SpotlightOpening.Shape.RECTANGLE
                        SpotlightOpening.Shape.RECTANGLE -> SpotlightOpening.Shape.OVAL
                    }

                    // Maintain dimensions when switching shapes
                    val updated = when (newShape) {
                        SpotlightOpening.Shape.CIRCLE -> {
                            val avgDim = (opening.width + opening.height) / 2f
                            opening.copy(
                                shape = newShape.name,
                                radius = avgDim / 2f,
                                width = avgDim,
                                height = avgDim,
                                size = avgDim
                            )
                        }
                        SpotlightOpening.Shape.SQUARE -> {
                            val avgDim = (opening.width + opening.height) / 2f
                            opening.copy(
                                shape = newShape.name,
                                radius = avgDim / 2f,
                                width = avgDim,
                                height = avgDim,
                                size = avgDim
                            )
                        }
                        SpotlightOpening.Shape.OVAL -> {
                            // Maintain existing width/height from rectangle
                            opening.copy(
                                shape = newShape.name,
                                radius = maxOf(opening.width, opening.height) / 2f
                            )
                        }
                        SpotlightOpening.Shape.RECTANGLE -> {
                            // Maintain existing width/height from oval
                            opening.copy(
                                shape = newShape.name,
                                size = maxOf(opening.width, opening.height)
                            )
                        }
                    }

                    spotlightDao.updateOpening(updated)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling shape", e)
            }
        }
    }

    fun toggleOpeningLock(openingId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val opening = spotlightDao.getOpeningById(openingId)
                if (opening != null) {
                    val newLockState = !opening.isLocked

                    // Track individually locked openings
                    if (newLockState && !isLockAllActive) {
                        individuallyLockedOpenings.add(openingId)
                    } else if (!newLockState && opening.isLocked && !isLockAllActive) {
                        individuallyLockedOpenings.remove(openingId)
                    }

                    spotlightDao.updateOpeningLockState(openingId, newLockState)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling lock", e)
            }
        }
    }

    fun toggleAllLocks() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                isLockAllActive = !isLockAllActive

                if (isLockAllActive) {
                    // Lock all openings
                    spotlightDao.updateAllOpeningsLockState(instanceId, true)
                } else {
                    // Unlock only openings that weren't individually locked
                    val allOpenings = spotlightDao.getOpeningsForInstance(instanceId)
                    allOpenings.forEach { opening ->
                        if (!individuallyLockedOpenings.contains(opening.openingId)) {
                            spotlightDao.updateOpeningLockState(opening.openingId, false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling all locks", e)
            }
        }
    }

    fun deleteOpening(openingId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentOpenings = _uiState.value.openings
                if (currentOpenings.size > 1 || currentOpenings.isEmpty()) {
                    // Only delete if not the last opening
                    spotlightDao.deleteOpening(openingId)
                    Log.d(TAG, "Deleted opening $openingId")
                } else {
                    Log.w(TAG, "Cannot delete last opening")
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(error = "Cannot delete the last opening") }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting opening", e)
            }
        }
    }

    fun updateOpeningFromDrag(openingId: Int, centerX: Float, centerY: Float) {
        // Immediate UI update for smooth dragging
        _uiState.update { currentState ->
            currentState.copy(
                openings = currentState.openings.map { opening ->
                    if (opening.openingId == openingId) {
                        opening.copy(centerX = centerX, centerY = centerY)
                    } else {
                        opening
                    }
                }
            )
        }

        // Persist to database
        updateOpeningPosition(openingId, centerX, centerY)
    }

    fun updateOpeningFromResize(opening: SpotlightOpening) {
        // Immediate UI update for smooth resizing
        _uiState.update { currentState ->
            currentState.copy(
                openings = currentState.openings.map { o ->
                    if (o.openingId == opening.openingId) opening else o
                }
            )
        }

        // Persist to database
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = mapOpeningToEntity(opening)
                spotlightDao.updateOpening(entity)
            } catch (e: Exception) {
                Log.e(TAG, "Error persisting resize", e)
            }
        }
    }

    fun setShowControls(show: Boolean) {
        _uiState.update { it.copy(showControls = show) }
    }

    fun deactivateInstance() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                spotlightDao.deactivateInstance(instanceId)
                Log.d(TAG, "Deactivated instance $instanceId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deactivating instance", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared for instance $instanceId")
    }

    // ===== Mapping Functions =====

    private fun mapEntityToOpening(entity: SpotlightOpeningEntity): SpotlightOpening {
        return SpotlightOpening(
            openingId = entity.openingId,
            centerX = entity.centerX,
            centerY = entity.centerY,
            radius = entity.radius,
            width = entity.width,
            height = entity.height,
            size = entity.size,
            shape = try {
                SpotlightOpening.Shape.valueOf(entity.shape)
            } catch (e: Exception) {
                SpotlightOpening.Shape.CIRCLE
            },
            isLocked = entity.isLocked,
            displayOrder = entity.displayOrder
        )
    }

    private fun mapOpeningToEntity(opening: SpotlightOpening): SpotlightOpeningEntity {
        return SpotlightOpeningEntity(
            openingId = opening.openingId,
            instanceId = instanceId,
            centerX = opening.centerX,
            centerY = opening.centerY,
            radius = opening.radius,
            width = opening.width,
            height = opening.height,
            size = opening.size,
            shape = opening.shape.name,
            isLocked = opening.isLocked,
            displayOrder = opening.displayOrder
        )
    }
}