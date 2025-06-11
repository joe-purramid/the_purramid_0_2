// SpinItemEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity // Make sure Room annotations are present if this is the intended Entity file
import androidx.room.PrimaryKey // Make sure Room annotations are present
import com.example.purramid.thepurramid.randomizers.SpinItemType
import java.util.UUID

@Entity(
    tableName = "spin_items",
    foreignKeys = [ForeignKey(
        entity = SpinListEntity::class,
        parentColumns = ["id"],
        childColumns = ["listId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["listId"])]
)
data class SpinItemEntity(
    @PrimaryKey val id: UUID,
    val listId: UUID, // Foreign key
    val itemType: SpinItemType,
    var content: String,
    var backgroundColor: Int? = null,
    val emojiList: List<String> = listOf() // For storing up to 10 emoji
)