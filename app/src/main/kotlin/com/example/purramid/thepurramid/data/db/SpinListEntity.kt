// SpinListEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "spin_lists")
data class SpinListEntity(
    @PrimaryKey
    val id: UUID = UUID.randomUUID(), // Unique ID for each list
    var title: String,
    val items: MutableList<SpinItemEntity> = mutableListOf()
)