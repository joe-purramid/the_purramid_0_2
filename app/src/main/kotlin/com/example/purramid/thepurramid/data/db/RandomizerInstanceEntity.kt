// RandomizerInstanceEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Room Entity representing the existence of a specific Randomizer window instance.
 * Its primary key links to the settings for this instance in SpinSettingsEntity.
 */
@Entity(tableName = "randomizer_instances")
data class RandomizerInstanceEntity(
    @PrimaryKey val instanceId: Int,
    val uuid: UUID = UUID.randomUUID(),
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1,  // -1 indicates WRAP_CONTENT
    val windowHeight: Int = -1, // -1 indicates WRAP_CONTENT
    val isActive: Boolean = true
)