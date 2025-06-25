// PurramidDatabase.kt
package com.example.purramid.thepurramid.data.db 

import
android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters


/**
 * The Room database for the Purramid application.
 * This contains tables for Clock and Randomizer features.
 */
@Database(
    entities = [
        // List all your entity classes here
        ClockStateEntity::class,
        TimeZoneBoundaryEntity::class,
        CityEntity::class,
        TimerStateEntity::class,
        RandomizerInstanceEntity::class,
        ScreenMaskStateEntity::class,
        SpinItemEntity::class,
        SpinListEntity::class,
        SpinSettingsEntity::class,
        SpotlightInstanceEntity::class,
        SpotlightOpeningEntity::class,
        TimerStateEntity::class,
        TrafficLightStateEntity::class
    ],
    version = 14, // Added nested timer and sound fields to timer_state
    exportSchema = false // Set to true if you want to export the schema to a file for version control (recommended for production apps)
)
@TypeConverters(Converters::class) // Register the TypeConverters class
abstract class PurramidDatabase : RoomDatabase() {

    /**
     * Abstract function to get the Data Access Objects
     * Room will generate the implementation.
     */
    abstract fun clockDao(): ClockDao
    abstract fun timeZoneDao(): TimeZoneDao
    abstract fun cityDao(): CityDao
    abstract fun randomizerDao(): RandomizerDao
    abstract fun screenMaskDao(): ScreenMaskDao
    abstract fun spotlightDao(): SpotlightDao
    abstract fun timerDao(): TimerDao
    abstract fun trafficLightDao(): TrafficLightDao

    companion object {
        @Volatile
        private var INSTANCE: PurramidDatabase? = null

        // Migration from 11 to 12: Add UUID to screen_mask_state
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE screen_mask_state ADD COLUMN uuid TEXT NOT NULL DEFAULT '${UUID.randomUUID()}'")
                database.execSQL("ALTER TABLE timer_state ADD COLUMN isNested INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE timer_state ADD COLUMN nestedX INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE timer_state ADD COLUMN nestedY INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE timer_state ADD COLUMN soundsEnabled INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE timer_state ADD COLUMN selectedSoundUri TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE timer_state ADD COLUMN musicUrl TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE timer_state ADD COLUMN recentMusicUrlsJson TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE timer_state ADD COLUMN showLapTimes INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Future migrations would be added here:
        // private val MIGRATION_12_13 = object : Migration(12, 13) { ... }
        // private val MIGRATION_13_14 = object : Migration(13, 14) { ... }

        fun getDatabase(context: Context): PurramidDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PurramidDatabase::class.java,
                    "purramid_database"
                )
                    .addMigrations(
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        Migration_13_14
                        // Add future migrations here: MIGRATION_12_13, MIGRATION_13_14, etc.
                    )
                    .fallbackToDestructiveMigrationOnDowngrade() // Only destroy on downgrade
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}