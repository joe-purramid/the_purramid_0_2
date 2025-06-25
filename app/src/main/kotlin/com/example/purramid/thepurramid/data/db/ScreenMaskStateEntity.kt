// ScreenMaskStateEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "screen_mask_states")
data class ScreenMaskStateEntity(
    @PrimaryKey val instanceId: Int,
    val uuid: String = UUID.randomUUID().toString(),
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val isLocked: Boolean,
    val billboardImageUri: String?,
    val isBillboardVisible: Boolean,
    val isControlsVisible: Boolean
)