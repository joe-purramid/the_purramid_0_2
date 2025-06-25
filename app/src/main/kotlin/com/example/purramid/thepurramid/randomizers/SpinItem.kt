// SpinList.kt
package com.example.purramid.thepurramid.randomizers

import java.util.UUID

data class SpinItem(
    val id: UUID = UUID.randomUUID(),
    val type: SpinItemType,
    private var _content: String, // For text and image path
    var backgroundColor: Int? = null,
    val emojiList: MutableList<String> = mutableListOf() // For storing up to 10 emoji
) {
    var content: String
        get() = _content
        set(value) {
            _content = when (type) {
                SpinItemType.TEXT -> {
                    if (value.length <= 27) {
                        value
                    } else {
                        value.substring(0, 27)
                    }
                }
                SpinItemType.IMAGE -> value // Store image path
                SpinItemType.EMOJI -> "" // Emoji are stored in emojiList
            }
        }

    init {
        content = _content
    }
}