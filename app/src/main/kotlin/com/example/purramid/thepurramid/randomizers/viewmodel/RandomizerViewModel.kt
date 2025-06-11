// RandomizerViewModel.kt
package com.example.purramid.thepurramid.randomizers.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.data.db.DEFAULT_SETTINGS_ID
import com.example.purramid.thepurramid.data.db.RandomizerDao
import com.example.purramid.thepurramid.data.db.RandomizerInstanceEntity
import com.example.purramid.thepurramid.data.db.SpinItemEntity
import com.example.purramid.thepurramid.data.db.SpinListEntity
import com.example.purramid.thepurramid.data.db.SpinSettingsEntity
import com.example.purramid.thepurramid.randomizers.RandomizerInstanceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch // keep this one for viewModelScope.launch
import kotlinx.coroutines.withContext

// Data structure to hold data needed by SpinDialView (can stay here or move to own file)
data class SpinDialViewData(
    val items: List<SpinItemEntity> = emptyList(),
    val settings: SpinSettingsEntity? = null
)

@HiltViewModel
class RandomizerViewModel @Inject constructor(
    private val randomizerDao: RandomizerDao,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        const val KEY_INSTANCE_ID = "instanceId"
        private const val TAG = "RandomizerViewModel"
    }

    // --- State ---
    internal val instanceId: UUID = savedStateHandle.get<String>(KEY_INSTANCE_ID)?.let {
        try { UUID.fromString(it) } catch (e: IllegalArgumentException) { null }
    } ?: UUID.randomUUID().also { newId ->
        savedStateHandle[KEY_INSTANCE_ID] = newId.toString()
        viewModelScope.launch(Dispatchers.IO) { // Add Dispatcher
            randomizerDao.saveInstance(RandomizerInstanceEntity(instanceId = newId))

            // Get default lists
            val allLists = randomizerDao.getAllSpinListsNonLiveData()
            val firstListId = allLists?.firstOrNull()?.id // Get the ID of the first list

            // Get settings
            val defaultSettings = randomizerDao.getDefaultSettings()
            val initialSettings = SpinSettingsEntity(instanceId = newId, currentListId = firstListId)
            randomizerDao.saveSettings(initialSettings)

            // Post value back to main thread if initializing LiveData from background
            withContext(Dispatchers.Main) {
                if (_spinDialData.value == null) { _spinDialData.value = SpinDialViewData() }
                _spinDialData.value = _spinDialData.value?.copy(settings = initialSettings)
                firstListId?.let {
                    savedStateHandle["currentListId"] = it.toString()
                }
            }
        }
    }

    private val _currentListId = savedStateHandle.getLiveData<String?>("currentListId")
        .map { it?.let { uuidString -> UUID.fromString(uuidString) } }

    val allSpinLists: LiveData<List<SpinListEntity>> = randomizerDao.getAllSpinLists() // Define only once

    private val _spinDialData = MutableLiveData<SpinDialViewData>()
    val spinDialData: LiveData<SpinDialViewData> = _spinDialData

    private val _isDropdownVisible = MutableLiveData<Boolean>(false)
    val isDropdownVisible: LiveData<Boolean> = _isDropdownVisible

    private val _spinResult = MutableLiveData<SpinItemEntity?>()
    val spinResult: LiveData<SpinItemEntity?> = _spinResult

    private val _displayedListOrder = MediatorLiveData<List<SpinListEntity>>()
    val displayedListOrder: LiveData<List<SpinListEntity>> = _displayedListOrder

    // Moved Sequence LiveData definitions here (top level)
    private val _sequenceList = MutableLiveData<List<SpinItemEntity>?>()
    val sequenceList: LiveData<List<SpinItemEntity>?> = _sequenceList
    private val _sequenceIndex = MutableLiveData<Int>(0)
    val sequenceIndex: LiveData<Int> = _sequenceIndex

    // Correct placement for currentListTitle
    val currentListTitle: LiveData<String?> = _currentListId.map { listId ->
        listId?.let { id ->
            allSpinLists.value?.firstOrNull { it.id == id }?.title
        }
    }

    init {
        if (_spinDialData.value == null) {
            _spinDialData.value = SpinDialViewData()
        }
        if (savedStateHandle.contains(KEY_INSTANCE_ID)) {
            loadDataForInstance(instanceId)
        }

        // Keep only one set of these observers
        _displayedListOrder.addSource(allSpinLists) { lists ->
            updateDisplayedListOrder(lists, _currentListId.value)
        }
        _displayedListOrder.addSource(_currentListId) { currentId ->
            updateDisplayedListOrder(allSpinLists.value, currentId)
        }

        _currentListId.observeForever { listId ->
            if (listId != null) {
                loadItemsForList(listId)
                clearSequence()
            } else {
                _spinDialData.value = _spinDialData.value?.copy(items = emptyList())
                clearSequence()
            }
        }
    }

    private fun updateDisplayedListOrder(fullList: List<SpinListEntity>?, currentId: UUID?) {
        // ... (implementation remains the same) ...
        if (fullList == null) {
            _displayedListOrder.value = emptyList()
            return
        }
        val sortedList = mutableListOf<SpinListEntity>()
        val currentItem = fullList.find { it.id == currentId }
        currentItem?.let { sortedList.add(it) }
        sortedList.addAll(
            fullList.filter { it.id != currentId }
                .sortedBy { it.title }
        )
        _displayedListOrder.value = sortedList
    }

    private fun loadDataForInstance(idToLoad: UUID) {
        viewModelScope.launch(Dispatchers.IO) { // Add Dispatcher
            val settings = randomizerDao.getSettingsForInstance(idToLoad)
            // Post value back to main thread
            withContext(Dispatchers.Main) {
                // Apply null check here
                _spinDialData.value = _spinDialData.value?.copy(settings = settings)
                if (_currentListId.value == null && settings?.currentListId != null) {
                    savedStateHandle["currentListId"] = settings.currentListId.toString()
                } else {
                    _currentListId.value?.let { loadItemsForList(it) }
                }
            }
        }
    }

    private fun loadItemsForList(listId: UUID) {
        viewModelScope.launch(Dispatchers.IO) { // Add Dispatcher
            val items = randomizerDao.getItemsForList(listId)
            val randomizedItems = items.shuffled()
            // Post value back to main thread
            withContext(Dispatchers.Main) {
                _spinDialData.value = _spinDialData.value?.copy(items = randomizedItems)
            }
        }
    }

    // --- UI Event Handlers ---
    fun handleSpinRequest() {
        // ... (implementation remains the same) ...
        val currentSettings = _spinDialData.value?.settings ?: return
        val currentItems = _spinDialData.value?.items ?: return
        if (currentItems.isEmpty()) {
            _spinResult.value = null
            return
        }
        if (currentSettings.isSequenceEnabled) {
            clearSequence()
        }
        if (currentSettings.isSpinEnabled) {
            _spinResult.value = null
        } else {
            val randomIndex = Random.nextInt(currentItems.size)
            val selectedItem = currentItems[randomIndex]
            _spinResult.value = selectedItem
            if (currentSettings.isSequenceEnabled) {
                generateSequence(selectedItem)
            } else {
                _spinDialData.postValue( // Use postValue if already on background, or switch context
                    _spinDialData.value?.copy(
                        items = currentItems.sortedByDescending { it.id == selectedItem.id }
                    )
                )
            }
        }
    }

    fun setSpinResult(result: SpinItemEntity?) {
        // ... (implementation remains the same) ...
        _spinResult.value = result
        val currentSettings = _spinDialData.value?.settings
        if (result != null && currentSettings?.isSequenceEnabled == true) {
            generateSequence(result)
        }
    }

    fun clearSpinResult() {
        // ... (implementation remains the same) ...
        _spinResult.value = null
    }

    fun toggleListDropdown() {
        // ... (implementation remains the same) ...
        _isDropdownVisible.value = !(_isDropdownVisible.value ?: false)
    }

    fun selectList(listId: UUID) {
        clearSequence()
        savedStateHandle["currentListId"] = listId.toString()
        _isDropdownVisible.value = false
        viewModelScope.launch(Dispatchers.IO) { // Add Dispatcher
            val settings = randomizerDao.getSettingsForInstance(instanceId)
            settings?.let {
                val updatedSettings = it.copy(currentListId = listId)
                randomizerDao.saveSettings(updatedSettings)
                withContext(Dispatchers.Main) {
                    _spinDialData.value = _spinDialData.value?.copy(settings = updatedSettings)
                }
            }
        }
    }

    // --- Sequence Logic Functions ---
    private fun generateSequence(firstItem: SpinItemEntity) {
        // ... (implementation remains the same) ...
        viewModelScope.launch(Dispatchers.IO) {
            val listId = firstItem.listId
            val allItemsForList = randomizerDao.getItemsForList(listId)
            if (allItemsForList.size <= 1) {
                withContext(Dispatchers.Main) {
                    _sequenceList.value = allItemsForList
                    _sequenceIndex.value = 0
                }
                return@launch
            }
            val remainingItems = allItemsForList.toMutableList()
            remainingItems.remove(firstItem)
            remainingItems.shuffle()
            val finalSequence = listOf(firstItem) + remainingItems
            withContext(Dispatchers.Main) {
                _sequenceList.value = finalSequence
                _sequenceIndex.value = 0
            }
        }
    }

    fun showNextSequenceItem() {
        // ... (implementation remains the same) ...
        val currentList = _sequenceList.value ?: return
        val currentIndex = _sequenceIndex.value ?: 0
        if (currentIndex < currentList.size - 1) {
            _sequenceIndex.value = currentIndex + 1
        }
    }

    fun showPreviousSequenceItem() {
        // ... (implementation remains the same) ...
        val currentIndex = _sequenceIndex.value ?: 0
        if (currentIndex > 0) {
            _sequenceIndex.value = currentIndex - 1
        }
    }

    fun clearSequence() {
        // ... (implementation remains the same) ...
        _sequenceList.value = null
        _sequenceIndex.value = 0
    }

    /**
     * Called when the user manually closes this randomizer instance.
     * Deletes the instance-specific settings and its registration from the database.
     */
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

    override fun onCleared() {
        super.onCleared()
        // No database deletion here
    }

    // --- Settings Update ---
    fun updateSettings(newSettings: SpinSettingsEntity) {
        viewModelScope.launch(Dispatchers.IO) { // Add Dispatcher
            // Corrected logic for applying sequence check
            var settingsToModify = newSettings.copy(instanceId = instanceId)
            if (settingsToModify.isSequenceEnabled) {
                settingsToModify = settingsToModify.copy(
                    isAnnounceEnabled = false,
                    isCelebrateEnabled = false
                )
            }
            else if (!settingsToModify.isAnnounceEnabled) {
                 settingsToModify = settingsToModify.copy(
                     isCelebrateEnabled = false
                 )
            }
            
            // Save the potentially modified settings
            randomizerDao.saveSettings(settingsToModify)
            // Update LiveData on main thread
            withContext(Dispatchers.Main) {
                if (_spinDialData.value == null) { _spinDialData.value = SpinDialViewData() }
                _spinDialData.value = _spinDialData.value?.copy(settings = settingsToModify)
            }
        }
    }


    // --- List and Item Modification Functions ---
    fun addList(title: String) {
        viewModelScope.launch(Dispatchers.IO) { // Add Dispatcher
            val newList = SpinListEntity(id = UUID.randomUUID(), title = title)
            randomizerDao.insertSpinList(newList)
        }
    }

    fun deleteList(list: SpinListEntity) {
        viewModelScope.launch(Dispatchers.IO) { // Add Dispatcher
            val wasCurrentList = (_currentListId.value == list.id) // Check before deleting
            randomizerDao.deleteItemsForList(list.id)
            randomizerDao.deleteSpinList(list)
            if (wasCurrentList) {
                // Update SavedStateHandle on main thread
                withContext(Dispatchers.Main) {
                    savedStateHandle["currentListId"] = null
                }
                // Also clear settings in DB
                randomizerDao.getSettingsForInstance(instanceId)?.let {
                    randomizerDao.saveSettings(it.copy(currentListId = null))
                    // No need to update _spinDialData here, _currentListId observer handles it
                }
            }
        }
    }

    fun updateListTitle(listId: UUID, newTitle: String) {
        viewModelScope.launch(Dispatchers.IO) { // Add Dispatcher
            randomizerDao.getSpinListById(listId)?.let { list ->
                randomizerDao.updateSpinList(list.copy(title = newTitle))
            }
        }
    }

    fun addItemToList(listId: UUID, item: SpinItemEntity) {
        // Assign new ID on add
        val itemToAdd = item.copy(listId = listId, id = UUID.randomUUID())
        viewModelScope.launch(Dispatchers.IO) { // Add Dispatcher
            randomizerDao.insertSpinItem(itemToAdd)
            if (_currentListId.value == listId) {
                // Reload items which posts back to main thread
                loadItemsForList(listId)
            }
        }
    }

    fun updateItem(item: SpinItemEntity) {
        viewModelScope.launch(Dispatchers.IO) { // Add Dispatcher
            randomizerDao.updateSpinItem(item)
            if (_currentListId.value == item.listId) {
                loadItemsForList(item.listId)
            }
        }
    }

    fun deleteItem(item: SpinItemEntity) {
        viewModelScope.launch(Dispatchers.IO) { // Add Dispatcher
            randomizerDao.deleteItem(item)
            if (_currentListId.value == item.listId) {
                loadItemsForList(item.listId)
            }
        }
    }
}
