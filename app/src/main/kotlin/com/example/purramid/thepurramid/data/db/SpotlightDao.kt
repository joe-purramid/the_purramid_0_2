// SpotlightDao.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Delete

@Dao
interface SpotlightDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(spotlight: SpotlightStateEntity)

    @Query("SELECT * FROM spotlight_state")
    suspend fun getAllSpotlights(): List<SpotlightStateEntity>

    // Using the entity directly for delete might be simpler if you have it
    @Delete
    suspend fun deleteSpotlight(spotlight: SpotlightStateEntity)

    // Or delete by ID
    @Query("DELETE FROM spotlight_state WHERE id = :spotlightId")
    suspend fun deleteSpotlightById(spotlightId: Int)


    @Query("DELETE FROM spotlight_state")
    suspend fun clearAll()
}