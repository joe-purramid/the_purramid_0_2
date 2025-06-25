package com.example.purramid.thepurramid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TimeZoneDao {
    // ... (methods remain the same)
    @Query("SELECT * FROM time_zone_boundaries")
    suspend fun getAllBoundaries(): List<TimeZoneBoundaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(boundaries: List<TimeZoneBoundaryEntity>)

    @Query("SELECT COUNT(*) FROM time_zone_boundaries")
    suspend fun getCount(): Int
}