// ClockAlarmDao.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClockAlarmDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: ClockAlarmEntity): Long
    
    @Update
    suspend fun updateAlarm(alarm: ClockAlarmEntity)
    
    @Query("SELECT * FROM clock_alarms WHERE clockId = :clockId ORDER BY time ASC")
    fun getAlarmsForClock(clockId: Int): Flow<List<ClockAlarmEntity>>
    
    @Query("SELECT * FROM clock_alarms WHERE isEnabled = 1 ORDER BY time ASC")
    fun getAllActiveAlarms(): Flow<List<ClockAlarmEntity>>
    
    @Query("SELECT * FROM clock_alarms WHERE alarmId = :alarmId")
    suspend fun getAlarmById(alarmId: Long): ClockAlarmEntity?
    
    @Query("DELETE FROM clock_alarms WHERE alarmId = :alarmId")
    suspend fun deleteAlarm(alarmId: Long)
    
    @Query("DELETE FROM clock_alarms WHERE clockId = :clockId")
    suspend fun deleteAlarmsForClock(clockId: Int)
    
    @Query("UPDATE clock_alarms SET isEnabled = :enabled WHERE alarmId = :alarmId")
    suspend fun setAlarmEnabled(alarmId: Long, enabled: Boolean)
} 