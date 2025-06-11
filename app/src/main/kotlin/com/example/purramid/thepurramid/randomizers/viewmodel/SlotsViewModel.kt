package com.example.purramid.thepurramid.randomizers.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.data.db.RandomizerDao
import com.example.purramid.thepurramid.data.db.RandomizerInstanceEntity
import com.example.purramid.thepurramid.data.db.SpinItemEntity
import com.example.purramid.thepurramid.data.db.SpinListEntity
import com.example.purramid.thepurramid.data.db.SpinSettingsEntity
import com.example.purramid.thepurramid.randomizers.SlotsColumnState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

// Data class to hold result for announcement
data class SlotsResult(val results: List<Pair<SlotsColumnState, SpinItemEntity?>>)

@HiltViewModel
class SlotsViewModel @Inject constructor(
    private val randomizerDao: RandomizerDao,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        // Key to get instanceId from SavedStateHandle (passed via NavArgs/Intent)
        // Ensure this matches the key used in navigation/intent passing
        const val KEY_INSTANCE_ID = "instanceId" // Or use RandomizerSettingsViewModel.KEY_INSTANCE_ID
        private const val TAG = "SlotsViewModel"
        const val DEFAULT_NUM_COLUMNS = 3
    }

    private val instanceId: UUID? = savedStateHandle.get<String>(KEY_INSTANCE_ID)?.let {
        try { UUID.fromString(it) } catch (e: IllegalArgumentException) { null }
    }

    // Overall settings for this instance
    private val _settings = MutableLiveData<SpinSettingsEntity?>()
    val settings: LiveData<SpinSettingsEntity?> = _settings

    // State for each individual column
    private val _columnStates = MutableLiveData<List<SlotsColumnState>>(emptyList())
    val columnStates: LiveData<List<SlotsColumnState>> = _columnStates

    // LiveData for available lists (used for selection dropdowns)
    val allSpinLists: LiveData<List<SpinListEntity>> = randomizerDao.getAllSpinLists()

    // Signals when spinning animation is active for a column (Map<columnIndex, Boolean>)
    private val _isSpinning = MutableLiveData<Map<Int, Boolean>>(emptyMap())
    val isSpinning: LiveData<Map<Int, Boolean>> = _isSpinning

    // Holds the final result after spinning to potentially trigger announcement
    private val _spinResult = MutableLiveData<SlotsResult?>()
    val spinResult: LiveData<SlotsResult?> = _spinResult

    // Error messages
    private val _errorEvent = MutableLiveData<String?>()
    val errorEvent: LiveData<String?> = _errorEvent

    // --- Cache for list items ---
    private val listItemsCache = ConcurrentHashMap<UUID, List<SpinItemEntity>>()
    private val fetchJobs = ConcurrentHashMap<UUID, Deferred<List<SpinItemEntity>>>()

    init {
        if (instanceId != null) {
            loadInitialState(instanceId)
        } else {
            _errorEvent.postValue("SlotsViewModel: Missing or invalid Instance ID.")
        }
        // Observe column states to pre-fetch items when lists are selected
        _columnStates.observeForever { states ->
            states?.forEach { state ->
                state.selectedListId?.let { listId ->
                    // Trigger fetch if not already cached or being fetched
                    if (!listItemsCache.containsKey(listId) && !fetchJobs.containsKey(listId)) {
                        fetchAndCacheItems(listId)
                    }
                }
            }
        }
        // Also observe allSpinLists to potentially clear cache if a list is deleted/updated externally
        allSpinLists.observeForever { lists ->
            // Basic check: remove cached items for lists that no longer exist
            val currentListIds = lists?.map { it.id }?.toSet() ?: emptySet()
            listItemsCache.keys.retainAll { it in currentListIds }
            fetchJobs.keys.retainAll { it in currentListIds }
        }
    }

    private fun loadInitialState(id: UUID) {
        viewModelScope.launch(Dispatchers.IO) {
            val loadedSettings = randomizerDao.getSettingsForInstance(id)
            withContext(Dispatchers.Main) {
                if (loadedSettings != null) {
                    _settings.value = loadedSettings
                    // Extract saved column states and number of columns from the loaded entity
                    val savedColumnStates = loadedSettings.slotsColumnStates
                    val numColumns = loadedSettings.numSlotsColumns

                    // Initialize column states based on saved data and number
                    val initialStates = initializeColumnStates(savedColumnStates, numColumns)
                    _columnStates.value = initialStates

                } else {
                    _errorEvent.value = "Settings not found for instance $id."
                    // Initialize with defaults if settings are missing
                    val defaultSettings = SpinSettingsEntity(instanceId = id) // Basic default
                    _settings.value = defaultSettings
                    _columnStates.value = initializeColumnStates(emptyList(), defaultSettings.numSlotsColumns)
                }
            }
        }
    }

    // Helper to ensure correct number of column states exist
    private fun initializeColumnStates(currentStates: List<SlotsColumnState>?, targetCount: Int): List<SlotsColumnState> {
        val initialStates = mutableListOf<SlotsColumnState>()
        // Use the provided list (or empty list if null)
        val validCurrentStates = currentStates ?: emptyList()
        for (i in 0 until targetCount) {
            // Find existing state for the current index, or create a new default one
            val existing = validCurrentStates.firstOrNull { it.columnIndex == i }
            initialStates.add(existing ?: SlotsColumnState(columnIndex = i))
        }
        return initialStates.take(targetCount) // Ensure exact count
    }

    // --- Actions ---

    fun selectListForColumn(columnIndex: Int, listId: UUID?) {
        val currentStates = _columnStates.value?.toMutableList() ?: return
        val currentState = currentStates.firstOrNull { it.columnIndex == columnIndex } ?: return

        if (currentState.selectedListId != listId) {
            val newState = currentState.copy(selectedListId = listId, currentItemId = null) // Reset item on list change
            val index = currentStates.indexOfFirst { it.columnIndex == columnIndex }
            if (index != -1) {
                currentStates[index] = newState
                _columnStates.value = currentStates // Update LiveData
                saveCurrentState() // Persist change
            }
        }
    }

    // --- Function to fetch and cache items ---
    private fun fetchAndCacheItems(listId: UUID): Deferred<List<SpinItemEntity>> {
        // Avoid launching multiple fetches for the same list
        fetchJobs[listId]?.let { return it }

        val job = viewModelScope.async(Dispatchers.IO) { // Use async to return Deferred
            try {
                val items = randomizerDao.getItemsForList(listId)
                listItemsCache[listId] = items
                items // Return items
            } catch (e: Exception) {
                // Handle potential DB error
                _errorEvent.postValue("Error fetching items for list $listId")
                emptyList<SpinItemEntity>() // Return empty on error
            } finally {
                fetchJobs.remove(listId) // Remove job once completed (success or fail)
            }
        }
        fetchJobs[listId] = job // Store the job
        return job
    }
    // --- Public function for Fragment to get items ---
    fun getItemsForColumn(columnIndex: Int): List<SpinItemEntity>? {
        val listId = _columnStates.value?.firstOrNull { it.columnIndex == columnIndex }?.selectedListId
        return listId?.let {
            listItemsCache[it] // Return cached items directly
            // Fetching is triggered reactively by columnStates observer in init.
            // If items aren't cached yet, the Fragment will get null/empty here,
            // but will get an update later when the cache is populated and columnStates triggers observer again.
        }
    }

    fun toggleLockForColumn(columnIndex: Int) {
        val currentStates = _columnStates.value?.toMutableList() ?: return
        val currentState = currentStates.firstOrNull { it.columnIndex == columnIndex } ?: return

        val newState = currentState.copy(isLocked = !currentState.isLocked)
        val index = currentStates.indexOfFirst { it.columnIndex == columnIndex }
        if (index != -1) {
            currentStates[index] = newState
            _columnStates.value = currentStates // Update LiveData
            saveCurrentState() // Persist change
        }
    }

    fun spinAllUnlocked() {
        val columnsToSpin = _columnStates.value?.filter { !it.isLocked && it.selectedListId != null } ?: emptyList()
        if (columnsToSpin.isEmpty()) return // Nothing to spin

        // Reset previous result
        _spinResult.value = null

        // Set spinning state for relevant columns
        val spinningMap = _isSpinning.value?.toMutableMap() ?: mutableMapOf()
        columnsToSpin.forEach { spinningMap[it.columnIndex] = true }
        _isSpinning.value = spinningMap

        viewModelScope.launch {
            // Simulate spin duration (adjust as needed, same as Spin mode?)
            val spinDuration = 2000L
            delay(spinDuration) // Simulate the time it takes to spin

            // Determine results
            determineResults(columnsToSpin)
        }
    }

    fun handleManualClose() {
        instanceId?.let { idToClose ->
            Log.d(TAG, "handleManualClose called for instanceId: $idToClose")
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    randomizerDao.deleteSettingsForInstance(idToClose)
                    randomizerDao.deleteInstance(RandomizerInstanceEntity(instanceId = idToClose))
                    Log.d(TAG, "Successfully deleted settings and instance record for $idToClose from DB.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting data for instance $idToClose from DB", e)
                }
            }
        } ?: Log.w(TAG, "handleManualClose called but instanceId is null.")
    }

    // --- Cleanup in onCleared ---
    override fun onCleared() {
        super.onCleared()
        // Cancel any ongoing fetch jobs
        fetchJobs.values.forEach { it.cancel() }
        // Clear observers added with observeForever if necessary
        // _columnStates.removeObserver(...)
        // allSpinLists.removeObserver(...)
    }

    private suspend fun determineResults(columnsSpun: List<SlotsColumnState>) {
        val finalResultsMap = mutableMapOf<Int, UUID?>()

        // Fetch items concurrently using the cache/fetch mechanism
        val itemFetchDeferreds = columnsSpun.associate { column ->
            column.columnIndex to column.selectedListId?.let { fetchAndCacheItems(it) }
        }

        withContext(Dispatchers.IO) { // Perform DB fetches off main thread
            columnsSpun.forEach { column ->
                val itemsDeferred = itemFetchDeferreds[column.columnIndex]
                val items = itemsDeferred?.await() ?: emptyList() // Wait for fetch if needed

                if (items.isNotEmpty()) {
                    val randomIndex = Random.nextInt(items.size)
                    finalResultsMap[column.columnIndex] = items[randomIndex].id
                } else {
                    finalResultsMap[column.columnIndex] = null // No result if list is empty
                }
            }
        }

        // Update state on main thread
        withContext(Dispatchers.Main) {
            val currentStates = _columnStates.value?.toMutableList() ?: mutableListOf()
            val finalColumnStates = mutableListOf<SlotsColumnState>()
            val resultForAnnouncement = mutableListOf<Pair<SlotsColumnState, SpinItemEntity?>>()

            currentStates.forEach { existingState ->
                // Get the new item ID if this column spun and had a result
                val spunNewItemId = if (finalResultsMap.containsKey(existingState.columnIndex)) {
                    finalResultsMap[existingState.columnIndex]
                } else {
                    null // This column didn't spin or had no items
                }

                // Determine the final item ID: use new ID if spun, otherwise keep current
                val finalItemId = spunNewItemId ?: existingState.currentItemId

                val finalState = existingState.copy(currentItemId = finalItemId)
                finalColumnStates.add(finalState)

                // Prepare result for announcement (include locked columns too)
                // Use cache to find item details efficiently
                val itemEntity = finalState.currentItemId?.let { itemId ->
                    finalState.selectedListId?.let { listId ->
                        listItemsCache[listId]?.firstOrNull { item -> item.id == itemId }
                    }
                }
                resultForAnnouncement.add(Pair(finalState, itemEntity))
            }


            _columnStates.value = finalColumnStates // Update state with final item IDs

            // Clear spinning state
            val spinningMap = _isSpinning.value?.toMutableMap() ?: mutableMapOf()
            columnsSpun.forEach { spinningMap[it.columnIndex] = false }
            _isSpinning.value = spinningMap

            // Set final result for announcement logic
            _spinResult.value = SlotsResult(resultForAnnouncement)

            // Persist the final state
            saveCurrentState()
        }
    }

    fun clearSpinResult() {
        _spinResult.value = null
    }

    fun clearErrorEvent() {
        _errorEvent.value = null
    }

    // --- Persistence ---
    private fun saveCurrentState() {
        if (instanceId == null) return
        val currentSettings = _settings.value ?: return
        val currentColumns = _columnStates.value ?: return

        // Create a *new* settings object with the updated column states list
        // Ensure numSlotsColumns is also correctly part of currentSettings if it can change elsewhere
        val settingsToSave = currentSettings.copy(
            slotsColumnStates = currentColumns
            // If numSlotsColumns can be changed in settings, make sure it's updated here too, e.g.:
            // numSlotsColumns = currentColumns.size // Or get from settings UI value
        )
        _settings.value = settingsToSave

        // Persist to database
        viewModelScope.launch(Dispatchers.IO) {
            // The TypeConverter for List<SlotsColumnState> in Converters.kt will handle the conversion
            randomizerDao.saveSettings(settingsToSave)
        }
    }
}