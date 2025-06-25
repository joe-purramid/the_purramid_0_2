// SpinList.kt
package com.example.purramid.thepurramid.randomizers

import java.util.UUID

data class SpinList(
    val id: UUID = UUID.randomUUID(), // Unique ID for each list
    var title: String,
    val items: MutableList<SpinItem> = mutableListOf()
)