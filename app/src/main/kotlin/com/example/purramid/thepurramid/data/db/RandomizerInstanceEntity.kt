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
    // The instanceId is the primary key and the only data needed in this table for now.
    @PrimaryKey val instanceId: UUID
    val type: String,
    var windowId: Int? = null
)