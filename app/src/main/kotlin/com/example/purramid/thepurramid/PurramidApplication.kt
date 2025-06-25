// PurramidApplication.kt
package com.example.purramid.thepurramid

import android.app.Application
import androidx.core.provider.FontRequest
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.FontRequestEmojiCompatConfig
import androidx.preference.PreferenceManager // Or use DataStore if preferred
import com.example.purramid.thepurramid.data.db.PurramidDatabase
import com.example.purramid.thepurramid.data.db.RandomizerDao
import com.example.purramid.thepurramid.data.db.SpinItemEntity
import com.example.purramid.thepurramid.data.db.SpinListEntity
import com.example.purramid.thepurramid.randomizers.SpinItemType // Ensure this import is correct
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.google.GoogleEmojiProvider
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltAndroidApp
class PurramidApplication : Application() {
    // Inject the database using Hilt (DatabaseModule provides it)
    @Inject
    lateinit var database: PurramidDatabase

    // Create an application-scoped coroutine scope
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main) // Or Dispatchers.IO

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_DEFAULT_LISTS_SEEDED = "default_lists_seeded"
    }

    override fun onCreate() {
        super.onCreate()
        val fontRequest = FontRequest(
            "com.google.android.gms.fonts", // Font provider authority
            "com.google.android.gms", // Font provider package
            "emoji compat Font Query", // Query string (can be anything)
            R.array.com_google_android_gms_fonts_certs // Certificate resources
        )
        val config = FontRequestEmojiCompatConfig(applicationContext, fontRequest)
            .setReplaceAll(true) // Replace all supported emojis
        EmojiCompat.init(config)
        seedDefaultRandomizerLists()
    }

    private fun seedDefaultRandomizerLists() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seeded = prefs.getBoolean(KEY_DEFAULT_LISTS_SEEDED, false)

        if (!seeded) {
            // Launch in IO context for database operations
            applicationScope.launch(Dispatchers.IO) {
                val dao = database.randomizerDao()
                val count = dao.getListCount()

                if (count == 0) {
                    // Get resources context to access strings
                    val res = applicationContext.resources

                    // --- Create lists for translation ---
                    // Using getStringArray for items assumes you defined them in strings.xml
                    createDefaultList(dao, res.getString(R.string.default_list_title_colors), res.getStringArray(R.array.default_list_items_colors).toList())
                    createDefaultList(dao, res.getString(R.string.default_list_title_continents), res.getStringArray(R.array.default_list_items_continents).toList()) // TODO: Define array
                    createDefaultList(dao, res.getString(R.string.default_list_title_oceans), res.getStringArray(R.array.default_list_items_oceans).toList()) // TODO: Define array

                    // --- Create lists with hardcoded items ---
                    createDefaultList(dao, res.getString(R.string.default_list_title_consonants), listOf("B", "C", "D", "F", "G", "H", "J", "K", "L", "M", "N", "P", "Q", "R", "S", "T", "V", "W", "X", "Y*", "Z"))
                    createDefaultList(dao, res.getString(R.string.default_list_title_numbers), listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"))
                    createDefaultList(dao, res.getString(R.string.default_list_title_vowels), listOf("A", "E", "I", "O", "U", "Y*"))

                    // Mark as seeded
                    with(prefs.edit()) {
                        putBoolean(KEY_DEFAULT_LISTS_SEEDED, true)
                        apply()
                    }
                } else {
                    // Database wasn't empty, but flag was false? Set flag anyway.
                    with(prefs.edit()) {
                        putBoolean(KEY_DEFAULT_LISTS_SEEDED, true)
                        apply()
                    }
                }
            }
        }
    }

    // Helper function for seeding (runs on IO dispatcher from caller)
    private suspend fun createDefaultList(dao: RandomizerDao, title: String, itemContents: List<String>) {
        val listId = UUID.randomUUID()
        dao.insertSpinList(SpinListEntity(id = listId, title = title))
        val items = itemContents.map { content ->
            SpinItemEntity(
                id = UUID.randomUUID(),
                listId = listId,
                itemType = SpinItemType.TEXT,
                content = content,
                backgroundColor = null, // Let SpinDialView handle auto-color or specific logic
                emojiList = emptyList()
            )
        }
        dao.insertSpinItems(items)
    }
}