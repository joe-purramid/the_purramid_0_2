// SpotlightOpeningEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for individual spotlight openings (holes) within an overlay.
 * Multiple openings can exist within a single service instance overlay.
 */
@Entity(
    tableName = "spotlight_openings",
    foreignKeys = [
        ForeignKey(
            entity = SpotlightInstanceEntity::class,
            parentColumns = ["instanceId"],
            childColumns = ["instanceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("instanceId")]
)
data class SpotlightOpeningEntity(
    @PrimaryKey(autoGenerate = true) val openingId: Int = 0,
    val instanceId: Int,  // Which service instance owns this opening
    val centerX: Float,
    val centerY: Float,
    val radius: Float,    // For circles
    val width: Float,     // For rectangles/ovals
    val height: Float,    // For rectangles/ovals
    val size: Float,      // For squares (and legacy compatibility)
    val shape: String,    // CIRCLE, SQUARE, OVAL, RECTANGLE
    val isLocked: Boolean = false,
    val displayOrder: Int = 0  // For consistent rendering order
)