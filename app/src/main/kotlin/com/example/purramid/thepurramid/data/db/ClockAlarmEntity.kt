// ClockAlarmEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@Entity(tableName = "clock_alarms")
data class ClockAlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val alarmId: Long = 0,
    val clockId: Int, // Associated clock instance
    val uuid: UUID = UUID.randomUUID(),
    val time: LocalTime, // Alarm time
    val timeZoneId: String? = null, // Timezone for the alarm (null = system default)
    val isEnabled: Boolean = true,
    val label: String = "", // Optional alarm label
    val daysOfWeek: Int = 0, // Bit flags for days (0 = one-time, 1-127 = repeat)
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true
) {
    // Helper method to get the effective timezone
    fun getEffectiveTimeZone(): ZoneId {
        return timeZoneId?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
    }
} 