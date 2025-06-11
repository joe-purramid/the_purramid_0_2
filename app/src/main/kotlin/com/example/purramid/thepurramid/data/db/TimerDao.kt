// TimerDao.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TimerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: TimerStateEntity)

    @Query("SELECT * FROM timer_state WHERE timerId = :id")
    suspend fun getById(id: Int): TimerStateEntity?

    @Query("SELECT * FROM timer_state")
    suspend fun getAllStates(): List<TimerStateEntity> // For potential multi-instance restore

    @Query("DELETE FROM timer_state WHERE timerId = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM timer_state")
    suspend fun clearAll() // Optional
}