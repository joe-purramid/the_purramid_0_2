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
    suspend fun getAllStates(): List<TimerStateEntity>

    @Query("DELETE FROM timer_state WHERE timerId = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM timer_state")
    suspend fun clearAll()

    // Standardized methods for instance management
    @Query("SELECT COUNT(*) FROM timer_state")
    suspend fun getActiveInstanceCount(): Int

    @Query("SELECT * FROM timer_state WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): TimerStateEntity?

    // Additional standardized methods matching other app-intents
    @Query("SELECT timerId FROM timer_state")
    suspend fun getAllInstanceIds(): List<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM timer_state WHERE timerId = :id)")
    suspend fun instanceExists(id: Int): Boolean
}