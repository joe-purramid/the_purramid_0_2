// ListCreatorViewModel.kt
package com.example.purramid.thepurramid.randomizers.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.R // Import R
import com.example.purramid.thepurramid.data.db.RandomizerDao
import com.example.purramid.thepurramid.data.db.SpinItemEntity
import com.example.purramid.thepurramid.data.db.SpinListEntity
import com.example.purramid.thepurramid.randomizers.SpinItemType
import com.example.purramid.thepurramid.util.Event // Import Event wrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ListCreatorViewModel @Inject constructor(
    private val randomizerDao: RandomizerDao,
    private val savedStateHandle: SavedStateHandle
    private val application: Application
) : ViewModel() {

    companion object {
        const val ARG_LIST_ID = "listId"
        const val MAX_ITEM_LENGTH = 27
        const val MAX_ITEMS = 44
        const val MIN_ITEMS_FOR_NEW_LIST = 2
        private const val MAX_IMAGE_SIZE_BYTES = 3 * 1024 * 1024 // 3 MB
    }

    private val listIdArg: String? = savedStateHandle[ARG_LIST_ID]
    private var currentListId: UUID? = listIdArg?.let { UUID.fromString(it) }
    private val _isEditing = MutableLiveData<Boolean>(currentListId != null)
    val isEditing: LiveData<Boolean> = _isEditing
    private var idForSaving: UUID = currentListId ?: UUID.randomUUID()
    private var listEntitySaved = _isEditing.value ?: false

    private val _listTitle = MutableLiveData<String>("")
    val listTitle: LiveData<String> = _listTitle
    private val _internalItems = MutableLiveData<MutableList<SpinItemEntity>>(mutableListOf())
    val items: LiveData<List<SpinItemEntity>> = _internalItems.map { it.toList() }
    private val _canAddItem = MutableLiveData<Boolean>(true)
    val canAddItem: LiveData<Boolean> = _canAddItem

    // *** NEW: LiveData for error events ***
    private val _errorEvent = MutableLiveData<Event<Int>>()
    val errorEvent: LiveData<Event<Int>> = _errorEvent

    init {
        currentListId?.let { loadListDetails(it) }
            ?: run {
                idForSaving = UUID.randomUUID()
                listEntitySaved = false
                updateCanAddItemFlag()
            }
    }

    private fun loadListDetails(listId: UUID) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = randomizerDao.getSpinListById(listId)
                val listItems = randomizerDao.getItemsForList(listId)
                withContext(Dispatchers.Main) {
                    listEntitySaved = list != null
                    _listTitle.value = list?.title ?: ""
                    _internalItems.value = listItems.toMutableList()
                    updateCanAddItemFlag()
                }
            } catch (e: Exception) {
                Log.e("ListCreatorVM", "Error loading list details for $listId", e)
                _errorEvent.postValue(Event(R.string.error_list_creator_load_failed)) // TODO: Add string resource
            }
        }
    }

    fun updateListTitle(newTitle: String) {
        if (_listTitle.value != newTitle) {
            _listTitle.value = newTitle
        }
    }

    fun saveListTitleOnly() {
        val titleToSave = _listTitle.value ?: ""
        if (titleToSave.isNotBlank()) {
            val listEntity = SpinListEntity(id = idForSaving, title = titleToSave.trim())
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    randomizerDao.insertSpinList(listEntity)
                    listEntitySaved = true
                    if (_isEditing.value == false) {
                        withContext(Dispatchers.Main) { _isEditing.value = true }
                        currentListId = idForSaving
                    }
                } catch (e: Exception) {
                    Log.e("ListCreatorVM", "Error saving list title for $idForSaving", e)
                    _errorEvent.postValue(Event(R.string.error_list_creator_save_title_failed))
                }
            }
        }
    }

    fun isListStateValidForSaving(): Boolean {
        val titleIsNotBlank = _listTitle.value?.isNotBlank() ?: false
        val itemCount = _internalItems.value?.size ?: 0
        val wasOriginallyEditing = listIdArg != null
        if (!titleIsNotBlank) return false
        return if (wasOriginallyEditing) true else itemCount >= MIN_ITEMS_FOR_NEW_LIST
    }

    fun isNewListWithTitleButNotEnoughItems(): Boolean {
        val titleIsNotBlank = _listTitle.value?.isNotBlank() ?: false
        val itemCount = _internalItems.value?.size ?: 0
        val wasOriginallyEditing = listIdArg != null
        return !wasOriginallyEditing && titleIsNotBlank && itemCount < MIN_ITEMS_FOR_NEW_LIST
    }

    fun isNewBlankListWithNoItems(): Boolean {
        val titleIsBlank = _listTitle.value?.isBlank() ?: true
        val itemCount = _internalItems.value?.size ?: 0
        val wasOriginallyEditing = listIdArg != null
        return !wasOriginallyEditing && titleIsBlank && itemCount == 0
    }

    fun deleteCurrentListAndItems() {
        if (listEntitySaved) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    Log.d("ListCreatorVM", "Deleting potentially invalid list: $idForSaving")
                    randomizerDao.deleteItemsForList(idForSaving)
                    randomizerDao.deleteSpinList(SpinListEntity(id = idForSaving, title = ""))
                } catch(e: Exception) {
                    Log.e("ListCreatorVM", "Error deleting list $idForSaving", e)
                    _errorEvent.postValue(Event(R.string.error_list_creator_delete_list_failed))
                }
            }
        }
    }

    fun addNewItem() {
        if ((_internalItems.value?.size ?: 0) >= MAX_ITEMS) return
        saveListTitleOnly() // Ensure list entity exists

        val newItem = SpinItemEntity(
            id = UUID.randomUUID(), listId = idForSaving, itemType = SpinItemType.TEXT,
            content = "", backgroundColor = null, emojiList = emptyList()
        )
        val currentList = _internalItems.value ?: mutableListOf()
        currentList.add(newItem)
        _internalItems.value = currentList
        updateCanAddItemFlag()

        if (listEntitySaved) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    randomizerDao.insertSpinItem(newItem)
                } catch (e: Exception) {
                    Log.e("ListCreatorVM", "Error adding item to list $idForSaving", e)
                    _errorEvent.postValue(Event(R.string.error_list_creator_add_item_failed))
                    // Consider rolling back the UI change? Maybe remove item locally?
                    // withContext(Dispatchers.Main) { removeItemLocally(newItem.id) } // Example
                }
            }
        }
    }

    // Helper to remove item from local list if DB insert fails
    private fun removeItemLocally(itemId: UUID) {
        val currentList = _internalItems.value ?: return
        if (currentList.removeIf { it.id == itemId }) {
            _internalItems.value = currentList
            updateCanAddItemFlag()
        }
    }

    fun updateItemText(itemId: UUID, newText: String) {
        val limitedText = if (newText.length <= MAX_ITEM_LENGTH) newText else newText.substring(0, MAX_ITEM_LENGTH)
        val currentList = _internalItems.value ?: return
        val index = currentList.indexOfFirst { it.id == itemId }
        if (index != -1) {
            val currentItem = currentList[index]
            if (currentItem.content != limitedText && currentItem.itemType == SpinItemType.TEXT) { // Only update if text & type matches
                val updatedItem = currentItem.copy(content = limitedText)
                currentList[index] = updatedItem
                _internalItems.value = currentList
                if (listEntitySaved) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            randomizerDao.updateSpinItem(updatedItem)
                        } catch (e: Exception) {
                            Log.e("ListCreatorVM", "Error updating item text for ${itemId}", e)
                            _errorEvent.postValue(Event(R.string.error_list_creator_update_item_failed))
                            // Rollback UI?
                            // withContext(Dispatchers.Main) { rollbackItemChange(itemId, currentItem) } // Example
                        }
                    }
                }
            }
        }
    }

    fun deleteItemFromList(item: SpinItemEntity) {
        val currentList = _internalItems.value ?: return
        // Remove from local list first for immediate UI update
        if (currentList.removeIf { it.id == item.id }) {
            _internalItems.value = currentList
            updateCanAddItemFlag()
            if (listEntitySaved) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        randomizerDao.deleteSpinItem(item)
                    } catch (e: Exception) {
                        Log.e("ListCreatorVM", "Error deleting item ${item.id}", e)
                        _errorEvent.postValue(Event(R.string.error_list_creator_delete_item_failed))
                        // Rollback UI? Add item back locally?
                        // withContext(Dispatchers.Main) { addItemLocally(item) } // Example
                    }
                }
            }
        }
    }

    fun updateItemColor(itemId: UUID, newColor: Int?) {
        val currentList = _internalItems.value ?: return
        val index = currentList.indexOfFirst { it.id == itemId }
        if (index != -1) {
            val currentItem = currentList[index]
            if (currentItem.backgroundColor != newColor) {
                val updatedItem = currentItem.copy(backgroundColor = newColor)
                currentList[index] = updatedItem
                _internalItems.value = currentList
                if (listEntitySaved) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            randomizerDao.updateSpinItem(updatedItem)
                        } catch (e: Exception) {
                            Log.e("ListCreatorVM", "Error updating item color for ${itemId}", e)
                            _errorEvent.postValue(Event(R.string.error_list_creator_update_item_failed))
                            // Rollback UI?
                        }
                    }
                }
            }
        }
    }

    fun updateItemImage(itemId: UUID, imageUri: Uri?) {
        if (imageUri == null) return
        // --- Image Size Check ---
        var fileSize: Long = -1
        try {
            // Use application context to get ContentResolver
            application.contentResolver.openFileDescriptor(imageUri, "r")?.use { pfd ->
                fileSize = pfd.statSize
            }
        } catch (e: Exception) {
            Log.e("ListCreatorVM", "Could not determine file size for URI: $imageUri", e)
            _errorEvent.postValue(Event(R.string.error_list_creator_image_size_check_failed)) // TODO: Add string resource
            return // Stop processing if size check fails
        }

        if (fileSize == -1L) {
            Log.w("ListCreatorVM", "File size determination failed for URI: $imageUri")
            _errorEvent.postValue(Event(R.string.error_list_creator_image_size_check_failed)) // TODO: Add string resource
            return // Stop processing
        }

        if (fileSize > MAX_IMAGE_SIZE_BYTES) {
            Log.w("ListCreatorVM", "Image size ($fileSize bytes) exceeds limit ($MAX_IMAGE_SIZE_BYTES bytes) for URI: $imageUri")
            _errorEvent.postValue(Event(R.string.error_list_creator_image_too_large)) // TODO: Add string resource (e.g., "Image file is too large (Max 10MB)")
            return // Stop processing
        }

        val currentList = _internalItems.value ?: return
        val index = currentList.indexOfFirst { it.id == itemId }
        if (index != -1) {
            val currentItem = currentList[index]
            val uriString = imageUri.toString()
            if (currentItem.itemType != SpinItemType.IMAGE || currentItem.content != uriString) {
                val updatedItem = currentItem.copy(
                    itemType = SpinItemType.IMAGE, content = uriString, emojiList = emptyList()
                )
                currentList[index] = updatedItem
                _internalItems.value = currentList
                if (listEntitySaved) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            randomizerDao.updateSpinItem(updatedItem)
                        } catch (e: Exception) {
                            Log.e("ListCreatorVM", "Error updating item image for ${itemId}", e)
                            _errorEvent.postValue(Event(R.string.error_list_creator_update_item_failed))
                            // Rollback UI?
                        }
                    }
                }
            }
        }
    }

    fun addEmojiToItem(itemId: UUID, selectedEmoji: String?) {
        if (selectedEmoji.isNullOrBlank()) return
        val currentList = _internalItems.value ?: return
        val index = currentList.indexOfFirst { it.id == itemId }
        if (index != -1) {
            val currentItem = currentList[index]
            val currentEmojis = currentItem.emojiList.toMutableList()
            val MAX_EMOJI_PER_ITEM = 10
            if (currentEmojis.size < MAX_EMOJI_PER_ITEM) {
                currentEmojis.add(selectedEmoji)
                val updatedItem = currentItem.copy(
                    itemType = SpinItemType.EMOJI, emojiList = currentEmojis, content = ""
                )
                currentList[index] = updatedItem
                _internalItems.value = currentList
                if (listEntitySaved) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            randomizerDao.updateSpinItem(updatedItem)
                        } catch (e: Exception) {
                            Log.e("ListCreatorVM", "Error updating item emoji for ${itemId}", e)
                            _errorEvent.postValue(Event(R.string.error_list_creator_update_item_failed))
                            // Rollback UI?
                        }
                    }
                }
            } else {
                Log.w("ListCreatorVM", "Emoji limit reached for item $itemId")
                // Optionally post an info event: _errorEvent.postValue(Event(R.string.info_emoji_limit_reached))
            }
        }
    }

    private fun updateCanAddItemFlag() {
        _canAddItem.postValue((_internalItems.value?.size ?: 0) < MAX_ITEMS)
    }
}