// DatabaseModule.kt
package com.example.purramid.thepurramid.data.db

import android.content.Context
import com.example.purramid.thepurramid.di.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers


@Module
@InstallIn(SingletonComponent::class) // Provides dependencies for the entire app lifecycle
object DatabaseModule {

    @Provides
    @Singleton // Ensures only one instance of the DAO is created
    fun provideRandomizerDao(database: PurramidDatabase): RandomizerDao {
        return database.randomizerDao()
    }

    @Provides
    @Singleton
    fun provideClockDao(database: PurramidDatabase): ClockDao {
        return database.clockDao()
    }

    @Provides
    @Singleton
    fun provideTimeZoneDao(database: PurramidDatabase): TimeZoneDao {
        // Get TimeZoneDao from the PurramidDatabase instance
        return database.timeZoneDao()
    }

    @Provides
    @Singleton
    fun provideCityDao(database: PurramidDatabase): CityDao {
        return database.cityDao()
    }

    @Provides
    @Singleton
    fun provideScreenMaskDao(database: PurramidDatabase): ScreenMaskDao { // Added
        return database.screenMaskDao()
    }

    @Provides
    @Singleton
    fun provideSpotlightDao(database: PurramidDatabase): SpotlightDao {
        return database.spotlightDao()
    }

    @Provides
    @Singleton
    fun provideTimerDao(database: PurramidDatabase): TimerDao {
        return database.timerDao()
    }

    @Provides
    @Singleton
    fun provideTrafficLightDao(database: PurramidDatabase): TrafficLightDao {
        return database.trafficLightDao()
    }

    @Provides
    @Singleton // Ensures only one instance of the Database is created
    fun providePurramidDatabase(@ApplicationContext appContext: Context): PurramidDatabase {
        return PurramidDatabase.getDatabase(appContext)
    }

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}