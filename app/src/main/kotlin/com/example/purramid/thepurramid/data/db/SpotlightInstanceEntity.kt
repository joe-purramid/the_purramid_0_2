// SpotlightInstanceEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entity for tracking Spotlight service instances per Technical Architecture.
 * Each service instance can display one overlay with multiple openings.
 */
@Entity(tableName = "spotlight_instances")
data class SpotlightInstanceEntity(
    @PrimaryKey val instanceId: Int,
    val uuid: UUID = UUID.randomUUID(),
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1,  // MATCH_PARENT
    val windowHeight: Int = -1, // MATCH_PARENT
    val isActive: Boolean = true
)