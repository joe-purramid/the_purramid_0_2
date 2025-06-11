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

    @Query("SELECT * FROM clock_state WHERE clockId = :id")
    suspend fun getById(id: Int): ClockStateEntity?

    @Query("SELECT * FROM clock_state")
    suspend fun getAllStates(): List<ClockStateEntity> // To load all clocks on service start

    @Query("DELETE FROM clock_state WHERE clockId = :id")
    suspend fun deleteById(id: Int) // To delete when a clock is closed

    @Query("DELETE FROM clock_state")
    suspend fun clearAll() // Optional: For debugging or resetting
}