// ListEditorViewModel.kt
package com.example.purramid.thepurramid.randomizers.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.data.db.PurramidDatabase
import com.example.purramid.thepurramid.data.db.RandomizerDao
import com.example.purramid.thepurramid.data.db.SpinListEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Data class to hold list entity and its item count
data class ListWithCount(
    val listEntity: SpinListEntity,
    val itemCount: Int
)

class ListEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val randomizerDao: RandomizerDao =
        PurramidDatabase.getDatabase(application).randomizerDao()

    // Observe all lists from the DAO
    val allSpinLists: LiveData<List<SpinListEntity>> = randomizerDao.getAllSpinLists()

    // We might need item counts - calculating this reactively can be complex.
    // For simplicity now, we can fetch counts when needed or pass them around.
    // Let's just provide the delete function for now.

    /** Deletes the specified list and all its associated items from the database. */
    fun deleteList(list: SpinListEntity) {
        viewModelScope.launch(Dispatchers.IO) { // Use IO dispatcher for DB operations
            // Delete items first
            randomizerDao.deleteItemsForList(list.id)
            // Then delete the list itself
            randomizerDao.deleteSpinList(list)
            // TODO: Handle clearing currentListId if the deleted list was selected
            // This ideally happens in the RandomizerViewModel observing allSpinLists
            // or needs communication back if this VM doesn't know the current selection.
        }
    }

    // Example function to get count (can be inefficient if called often)
    suspend fun getItemCountForList(listId: UUID): Int {
       return withContext(Dispatchers.IO) {
           randomizerDao.getItemsForList(listId).size
       }
    }
}