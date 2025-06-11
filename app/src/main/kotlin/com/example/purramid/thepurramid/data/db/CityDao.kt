// CityDao.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CityDao {

    // Insert a list of cities, replace if conflict (useful for initial load)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cities: List<CityEntity>)

    // Query cities based on the timezone ID (uses the index)
    @Query("SELECT * FROM cities WHERE timezone_id = :tzId")
    suspend fun getCitiesByTimezone(tzId: String): List<CityEntity>

    // Query to check if the table is empty (for initial data load check)
    @Query("SELECT COUNT(*) FROM cities")
    suspend fun getCount(): Int

    // Optional: Clear all cities if needed for reloading/debugging
    @Query("DELETE FROM cities")
    suspend fun clearAll()
}