// ClockDao.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ClockDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: ClockStateEntity)

    @Query("SELECT * FROM clock_state WHERE clockId = :instanceId")
    suspend fun getByInstanceId(instanceId: Int): ClockStateEntity?

    @Query("SELECT * FROM clock_state")
    suspend fun getAllStates(): List<ClockStateEntity> // To load all clocks on service start

    @Query("SELECT COUNT(*) FROM clock_state")
    suspend fun getActiveInstanceCount(): Int

    @Query("DELETE FROM clock_state WHERE clockId = :instanceId")
    suspend fun deleteByInstanceId(instanceId: Int)

    @Query("DELETE FROM clock_state")
    suspend fun clearAll() // Optional: For debugging or resetting
}