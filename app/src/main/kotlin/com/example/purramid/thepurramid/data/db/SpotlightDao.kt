// SpotlightDao.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SpotlightDao {

    // ===== Instance Management =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateInstance(instance: SpotlightInstanceEntity)

    @Query("SELECT * FROM spotlight_instances WHERE instanceId = :instanceId")
    suspend fun getInstanceById(instanceId: Int): SpotlightInstanceEntity?

    @Query("SELECT * FROM spotlight_instances WHERE isActive = 1")
    suspend fun getActiveInstances(): List<SpotlightInstanceEntity>

    @Query("UPDATE spotlight_instances SET isActive = 0 WHERE instanceId = :instanceId")
    suspend fun deactivateInstance(instanceId: Int)

    @Query("DELETE FROM spotlight_instances WHERE instanceId = :instanceId")
    suspend fun deleteInstance(instanceId: Int)

    @Query("SELECT COUNT(*) FROM spotlight_instances WHERE isActive = 1")
    suspend fun getActiveInstanceCount(): Int

    // ===== Opening Management =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateOpening(opening: SpotlightOpeningEntity)

    @Insert
    suspend fun insertOpening(opening: SpotlightOpeningEntity): Long

    @Update
    suspend fun updateOpening(opening: SpotlightOpeningEntity)

    @Query("SELECT * FROM spotlight_openings WHERE instanceId = :instanceId ORDER BY displayOrder")
    suspend fun getOpeningsForInstance(instanceId: Int): List<SpotlightOpeningEntity>

    @Query("SELECT * FROM spotlight_openings WHERE instanceId = :instanceId ORDER BY displayOrder")
    fun getOpeningsForInstanceFlow(instanceId: Int): Flow<List<SpotlightOpeningEntity>>

    @Query("SELECT * FROM spotlight_openings WHERE openingId = :openingId")
    suspend fun getOpeningById(openingId: Int): SpotlightOpeningEntity?

    @Query("DELETE FROM spotlight_openings WHERE openingId = :openingId")
    suspend fun deleteOpening(openingId: Int)

    @Query("DELETE FROM spotlight_openings WHERE instanceId = :instanceId")
    suspend fun deleteAllOpeningsForInstance(instanceId: Int)

    @Query("SELECT COUNT(*) FROM spotlight_openings WHERE instanceId = :instanceId")
    suspend fun getOpeningCountForInstance(instanceId: Int): Int

    @Query("UPDATE spotlight_openings SET isLocked = :isLocked WHERE openingId = :openingId")
    suspend fun updateOpeningLockState(openingId: Int, isLocked: Boolean)

    @Query("UPDATE spotlight_openings SET isLocked = :isLocked WHERE instanceId = :instanceId")
    suspend fun updateAllOpeningsLockState(instanceId: Int, isLocked: Boolean)

    // ===== Transaction Methods =====

    @Transaction
    suspend fun deleteInstanceAndOpenings(instanceId: Int) {
        deleteAllOpeningsForInstance(instanceId)
        deleteInstance(instanceId)
    }

    // ===== Legacy Support (to be removed) =====

    @Query("DELETE FROM spotlight_state")
    suspend fun clearLegacyTable()
}