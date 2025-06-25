// ScreenMaskDao.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScreenMaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: ScreenMaskStateEntity)

    @Query("SELECT * FROM screen_mask_state WHERE instanceId = :id")
    suspend fun getById(id: Int): ScreenMaskStateEntity?

    @Query("SELECT * FROM screen_mask_state")
    suspend fun getAllStates(): List<ScreenMaskStateEntity>

    @Query("DELETE FROM screen_mask_state WHERE instanceId = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT COUNT(*) FROM screen_mask_state")
    suspend fun getCount(): Int

    @Query("SELECT MAX(instanceId) FROM screen_mask_state") // To help with new ID generation
    suspend fun getMaxInstanceId(): Int?

    @Query("DELETE FROM screen_mask_state")
    suspend fun clearAll()
}