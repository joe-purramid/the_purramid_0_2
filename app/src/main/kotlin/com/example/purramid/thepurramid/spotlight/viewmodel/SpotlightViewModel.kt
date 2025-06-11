// SpotlightViewModel.kt
package com.example.purramid.thepurramid.spotlight.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.data.db.SpotlightDao
import com.example.purramid.thepurramid.data.db.SpotlightStateEntity
import com.example.purramid.thepurramid.spotlight.SpotlightUiState // Import the UI state
import com.example.purramid.thepurramid.spotlight.SpotlightView // Import for Shape enum and Spotlight data class
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.maxOf // Added import
import kotlin.random.Random // For initial positioning if needed

@HiltViewModel
class SpotlightViewModel @Inject constructor(
    private val spotlightDao: SpotlightDao
) : ViewModel() {

    companion object {
        private const val TAG = "SpotlightViewModel"
        private const val MAX_SPOTLIGHTS = 4
        const val KEY_INSTANCE_ID = "spotlight_instance_id"
    }

    private val _uiState = MutableStateFlow(SpotlightUiState(isLoading = true))
    val uiState: StateFlow<SpotlightUiState> = _uiState.asStateFlow()

    init {
        loadSpotlights()
    }

    private fun loadSpotlights() {
        _uiState.update { it.copy(isLoading = true)} // Indicate loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entities = spotlightDao.getAllSpotlights()
                val spotlightsData = entities.map { mapEntityToData(it) }
                // Determine initial global shape from loaded data or default
                val currentShape = spotlightsData.firstOrNull()?.shape ?: SpotlightView.Spotlight.Shape.CIRCLE

                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            spotlights = spotlightsData,
                            globalShape = currentShape,
                            isLoading = false,
                            error = null
                        )
                    }
                }
                Log.d(TAG, "Loaded ${spotlightsData.size} spotlights.")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading spotlights from DB", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, error = "Failed to load spotlights") }
                }
            }
        }
    }

    fun addSpotlight(screenWidth: Int, screenHeight: Int) {
        if (_uiState.value.spotlights.size >= MAX_SPOTLIGHTS) {
            Log.w(TAG, "Max spotlights reached")
            _uiState.update { it.copy(error = "Maximum number of spotlights reached") } // Inform UI
            return
        }

        val currentShape = _uiState.value.globalShape
        val initialRadius = 150f
        val initialSize = initialRadius * 2f
        val initialWidth = if(currentShape == SpotlightView.Spotlight.Shape.OVAL || currentShape == SpotlightView.Spotlight.Shape.RECTANGLE) initialRadius * 2 * 1.5f else initialSize
        val initialHeight = if(currentShape == SpotlightView.Spotlight.Shape.OVAL || currentShape == SpotlightView.Spotlight.Shape.RECTANGLE) initialRadius * 2 / 1.5f else initialSize
        // Add some offset/randomness to initial position
        val offsetX = Random.nextInt(-50, 51) * (_uiState.value.spotlights.size + 1)
        val offsetY = Random.nextInt(-50, 51) * (_uiState.value.spotlights.size + 1)
        val initialX = (screenWidth / 2f + offsetX).coerceIn(initialWidth / 2f, screenWidth - initialWidth / 2f)
        val initialY = (screenHeight / 2f + offsetY).coerceIn(initialHeight / 2f, screenHeight - initialHeight / 2f)

        // Create the data object (ID is 0 for Room auto-generation)
        val newSpotlightData = SpotlightView.Spotlight(
            id = 0,
            centerX = initialX,
            centerY = initialY,
            radius = initialRadius,
            shape = currentShape,
            width = initialWidth,
            height = initialHeight,
            size = if(currentShape == SpotlightView.Spotlight.Shape.SQUARE || currentShape == SpotlightView.Spotlight.Shape.RECTANGLE) maxOf(initialWidth, initialHeight) else initialSize
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entityToInsert = mapDataToEntity(newSpotlightData)
                spotlightDao.insertOrUpdate(entityToInsert)
                // Reload all spotlights to get the new one with its generated ID
                // Trigger load on main thread to update UI state
                withContext(Dispatchers.Main) { loadSpotlights() }
                Log.d(TAG, "Added new spotlight to DB.")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding spotlight to DB", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(error = "Failed to add spotlight") }
                }
            }
        }
    }

    fun updateSpotlightState(updatedSpotlightData: SpotlightView.Spotlight) {
         viewModelScope.launch(Dispatchers.IO) {
             try {
                 val entity = mapDataToEntity(updatedSpotlightData)
                 spotlightDao.insertOrUpdate(entity)
                 // Update local state immediately for smoother UI
                 withContext(Dispatchers.Main) {
                     _uiState.update { currentState ->
                         val updatedList = currentState.spotlights.map {
                             if (it.id == updatedSpotlightData.id) updatedSpotlightData else it
                         }
                         currentState.copy(spotlights = updatedList, error = null) // Clear previous error on success
                     }
                 }
                 Log.d(TAG, "Updated spotlight ${updatedSpotlightData.id} in DB.")
             } catch (e: Exception) {
                 Log.e(TAG, "Error updating spotlight ${updatedSpotlightData.id} in DB", e)
                 withContext(Dispatchers.Main) {
                      _uiState.update { it.copy(error = "Failed to update spotlight") }
                 }
             }
         }
    }

    fun deleteSpotlight(spotlightId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                spotlightDao.deleteSpotlightById(spotlightId)
                 withContext(Dispatchers.Main) {
                    _uiState.update { currentState ->
                        val updatedList = currentState.spotlights.filterNot { it.id == spotlightId }
                        currentState.copy(spotlights = updatedList, error = null)
                    }
                }
                Log.d(TAG, "Deleted spotlight $spotlightId from DB.")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting spotlight $spotlightId from DB", e)
                 withContext(Dispatchers.Main) {
                      _uiState.update { it.copy(error = "Failed to delete spotlight") }
                 }
            }
        }
    }

    fun cycleGlobalShape() {
        val currentShape = _uiState.value.globalShape
        val nextShape = when (currentShape) {
            SpotlightView.Spotlight.Shape.CIRCLE -> SpotlightView.Spotlight.Shape.SQUARE
            SpotlightView.Spotlight.Shape.SQUARE -> SpotlightView.Spotlight.Shape.OVAL
            SpotlightView.Spotlight.Shape.OVAL -> SpotlightView.Spotlight.Shape.RECTANGLE
            SpotlightView.Spotlight.Shape.RECTANGLE -> SpotlightView.Spotlight.Shape.CIRCLE
        }
        setGlobalShape(nextShape) // Call internal function
    }


    // Internal function to apply shape change and save
    private fun setGlobalShape(newShape: SpotlightView.Spotlight.Shape) {
         val currentSpotlights = _uiState.value.spotlights
         val updatedSpotlights = currentSpotlights.map { spotlight ->
             // Maintain size/proportions (logic moved from SpotlightView)
             val avgDim = (spotlight.width + spotlight.height) / 2f
             val updatedData = spotlight.copy(shape = newShape)
             when (newShape) {
                 SpotlightView.Spotlight.Shape.CIRCLE -> {
                     updatedData.radius = avgDim / 2f
                     updatedData.width = updatedData.radius * 2
                     updatedData.height = updatedData.radius * 2
                     updatedData.size = updatedData.radius * 2
                 }
                 SpotlightView.Spotlight.Shape.SQUARE -> {
                     updatedData.size = avgDim
                     updatedData.width = updatedData.size
                     updatedData.height = updatedData.size
                     updatedData.radius = updatedData.size / 2f
                 }
                 SpotlightView.Spotlight.Shape.OVAL -> {
                     updatedData.radius = avgDim / 2f
                     updatedData.width = updatedData.radius * 2 * 1.5f // Keep aspect ratio logic consistent
                     updatedData.height = updatedData.radius * 2 / 1.5f
                     updatedData.size = maxOf(updatedData.width, updatedData.height)
                 }
                 SpotlightView.Spotlight.Shape.RECTANGLE -> {
                     updatedData.radius = avgDim / 2f
                     updatedData.width = updatedData.radius * 2 * 1.5f
                     updatedData.height = updatedData.radius * 2 / 1.5f
                     updatedData.size = maxOf(updatedData.width, updatedData.height)
                 }
             }
             // Make sure center remains the same
             updatedData.centerX = spotlight.centerX
             updatedData.centerY = spotlight.centerY
             updatedData // return the modified copy
         }

         // Update UI State on Main Thread
         _uiState.update { it.copy(spotlights = updatedSpotlights, globalShape = newShape, error = null) }

         // Save all updated entities to DB
         viewModelScope.launch(Dispatchers.IO) {
             var saveError = false
             updatedSpotlights.forEach { updatedData ->
                 try {
                     spotlightDao.insertOrUpdate(mapDataToEntity(updatedData))
                 } catch (e: Exception) {
                      Log.e(TAG, "Error saving updated shape for spotlight ${updatedData.id}", e)
                      saveError = true
                 }
             }
             if (saveError) {
                 withContext(Dispatchers.Main) {
                      _uiState.update { it.copy(error = "Failed to save some spotlight shapes") }
                 }
             }
         }
    }

    // --- Mappers ---
    private fun mapEntityToData(entity: SpotlightStateEntity): SpotlightView.Spotlight {
        return SpotlightView.Spotlight(
            id = entity.id,
            centerX = entity.centerX,
            centerY = entity.centerY,
            radius = entity.radius,
            shape = try { SpotlightView.Spotlight.Shape.valueOf(entity.shape) } catch (e: Exception) { SpotlightView.Spotlight.Shape.CIRCLE },
            width = entity.width,
            height = entity.height,
            size = entity.size
        )
    }

    // Map data to entity (handle ID: 0 for inserts, specific ID for updates)
    private fun mapDataToEntity(data: SpotlightView.Spotlight): SpotlightStateEntity {
        // If ID is 0, let Room auto-generate. If it's non-zero, use it for update.
        return SpotlightStateEntity(
            id = if (data.id == 0) 0 else data.id,
            centerX = data.centerX,
            centerY = data.centerY,
            radius = data.radius,
            shape = data.shape.name,
            width = data.width,
            height = data.height,
            size = data.size
        )
    }

    /**
     * Called by SpotlightService when a ViewModel instance is being removed.
     * This is the place for any specific cleanup related to this ViewModel's data,
     * for example, deleting its associated spotlights from the database if that's the desired behavior.
     * Note: `onCleared()` is called automatically by the ViewModelStore when the store itself is cleared (e.g., service onDestroy).
     */
    fun deleteState() {
        Log.d(TAG, "deleteState() called for ViewModel instance. Implement DB cleanup logic if needed for this instance.")
        // Example: If each ViewModel instance is tied to a specific set of spotlights
        // that should be deleted when this instance is removed:
        // val currentInstanceId = savedStateHandle.get<Int>(KEY_INSTANCE_ID) // Assuming you have access to instanceId
        // if (currentInstanceId != null) {
        //     viewModelScope.launch(Dispatchers.IO) {
        //         try {
        //             // spotlightDao.deleteAllSpotlightsForInstance(currentInstanceId) // Hypothetical DAO method
        //             Log.d(TAG, "Deleted all DB entries for instance $currentInstanceId")
        //         } catch (e: Exception) {
        //             Log.e(TAG, "Error deleting DB entries for instance $currentInstanceId", e)
        //         }
        //     }
        // }
    }
}