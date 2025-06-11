// TrafficLightDao.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrafficLightDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: TrafficLightStateEntity)

    @Query("SELECT * FROM traffic_light_state WHERE instanceId = :id")
    suspend fun getById(id: Int): TrafficLightStateEntity?

    @Query("DELETE FROM traffic_light_state WHERE instanceId = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM traffic_light_state")
    suspend fun getAllStates(): List<TrafficLightStateEntity> // For potential multi-instance restore

    @Query("DELETE FROM traffic_light_state")
    suspend fun clearAll() // For debugging or reset
}