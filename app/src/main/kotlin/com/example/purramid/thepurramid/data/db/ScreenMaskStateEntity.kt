// ScreenMaskStateEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "screen_mask_state")
data class ScreenMaskStateEntity(
    @PrimaryKey // instanceId will be unique
    val instanceId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val isLocked: Boolean,
    val billboardImageUri: String?,
    val isBillboardVisible: Boolean,
    val isControlsVisible: Boolean
)