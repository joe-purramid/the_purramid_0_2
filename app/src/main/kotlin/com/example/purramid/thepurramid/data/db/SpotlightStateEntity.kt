// SpotlightStateEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "spotlight_state")
data class SpotlightStateEntity(
    @PrimaryKey(autoGenerate = true) // Use auto-generated ID
    val id: Int = 0, // Auto-generated primary key
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val shape: String, // Store enum name as String
    val width: Float,
    val height: Float,
    val size: Float
)