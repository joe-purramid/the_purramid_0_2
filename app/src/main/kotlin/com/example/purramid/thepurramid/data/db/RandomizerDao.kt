// RandomizerDao.kt
package com.example.purramid.thepurramid.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import java.util.UUID

/**
 * Data Access Object (DAO) for Randomizer related entities.
 * Provides methods to interact with the Purramid application's Randomizer database tables.
 */

// Define a constant UUID for the default settings record
val DEFAULT_SETTINGS_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

@Dao
interface RandomizerDao {

    // --- List Operations ---
    /** Inserts a SpinListEntity. If it already exists, replaces it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpinList(list: SpinListEntity)

    /** Updates an existing SpinListEntity. */
    @Update
    suspend fun updateSpinList(list: SpinListEntity)

    /** Deletes a SpinListEntity. Also requires deleting associated items separately. */
    @Delete
    suspend fun deleteSpinList(list: SpinListEntity)

    /** Gets all SpinListEntities ordered alphabetically by title, observed via LiveData. */
    @Query("SELECT * FROM spin_lists ORDER BY title ASC")
    fun getAllSpinLists(): LiveData<List<SpinListEntity>> // LiveData for easy observation in ViewModel

    /** Gets a specific SpinListEntity by its ID. Returns null if not found. */
    @Query("SELECT * FROM spin_lists WHERE id = :listId")
    suspend fun getSpinListById(listId: UUID): SpinListEntity?


    // --- Item Operations ---
    /** Inserts a SpinItemEntity. If it already exists, replaces it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpinItem(item: SpinItemEntity)

    /** Inserts a list of SpinItemEntities. If any already exist, replaces them. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpinItems(items: List<SpinItemEntity>)

    /** Updates an existing SpinItemEntity. */
    @Update
    suspend fun updateSpinItem(item: SpinItemEntity)

    /** Deletes a SpinItemEntity. */
    @Delete
    suspend fun deleteSpinItem(item: SpinItemEntity)

    /** Deletes all SpinItemEntities associated with a specific list ID. */
    @Query("DELETE FROM spin_items WHERE listId = :listId")
    suspend fun deleteItemsForList(listId: UUID)

    /** Gets all SpinItemEntities associated with a specific list ID. */
    @Query("SELECT * FROM spin_items WHERE listId = :listId")
    suspend fun getItemsForList(listId: UUID): List<SpinItemEntity>


    // --- Settings Operations ---
    /** Inserts or replaces settings for a specific randomizer instance. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: SpinSettingsEntity)

    /** Gets the default settings entity using the predefined ID. */
    @Query("SELECT * FROM spin_settings WHERE instanceId = :defaultId")
    suspend fun getDefaultSettings(defaultId: UUID = DEFAULT_SETTINGS_ID): SpinSettingsEntity?

    /** Gets the settings for a specific randomizer instance. Returns null if not found. */
    @Query("SELECT * FROM spin_settings WHERE instanceId = :instanceId")
    suspend fun getSettingsForInstance(instanceId: UUID): SpinSettingsEntity?

    /** Saves settings as the default settings, replacing any existing default. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDefaultSettings(settings: SpinSettingsEntity) // Ensure settings.instanceId == DEFAULT_SETTINGS_ID before calling

    /** Deletes the settings associated with a specific instance ID. */
    @Query("DELETE FROM spin_settings WHERE instanceId = :instanceId")
    suspend fun deleteSettingsForInstance(instanceId: UUID)


    // --- Instance Operations ---
    /** Gets the total number of lists currently stored. */
    @Query("SELECT COUNT(*) FROM spin_lists")
    suspend fun getListCount(): Int

    /** Gets all lists ordered alphabetically by title (non-LiveData version). */
    @Query("SELECT * FROM spin_lists ORDER BY title ASC")
    suspend fun getAllSpinListsNonLiveData(): List<SpinListEntity>? // Nullable in case of DB error

    /** Inserts or replaces a record of an open randomizer instance. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveInstance(instance: RandomizerInstanceEntity)

    /** Gets all instance records *excluding* the default settings record ID. */
    @Query("SELECT * FROM randomizer_instances WHERE instanceId != :defaultId")
    suspend fun getAllNonDefaultInstances(defaultId: UUID = DEFAULT_SETTINGS_ID): List<RandomizerInstanceEntity>

    /** Deletes a specific randomizer instance record. */
    @Delete
    suspend fun deleteInstance(instance: RandomizerInstanceEntity)

    /** Deletes all randomizer instance records. Use with caution. */
    @Query("DELETE FROM randomizer_instances")
    suspend fun deleteAllInstances()

    // --- Combined Operations (Optional Examples - Requires Relations setup) ---
    /*
    // Requires defining a data class like data class SpinListWithItems(
    //    @Embedded val list: SpinListEntity,
    //    @Relation(parentColumn = "id", entityColumn = "listId")
    //    val items: List<SpinItemEntity>
    // )
    @Transaction // Ensures atomic operation
    @Query("SELECT * FROM spin_lists WHERE id = :listId")
    suspend fun getListWithItems(listId: UUID): SpinListWithItems?

    @Transaction
    @Query("SELECT * FROM spin_lists ORDER BY title ASC")
    fun getAllListsWithItems(): LiveData<List<SpinListWithItems>>
    */

    // Add other specific queries as needed for your application logic
}