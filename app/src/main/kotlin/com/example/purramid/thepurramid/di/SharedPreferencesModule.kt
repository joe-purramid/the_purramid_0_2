// SharedPreferencesModule.kt
package com.example.purramid.thepurramid.di

import android.content.Context
import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ClockPrefs
    fun provideClockPreferences(@ApplicationContext context: Context): SharedPreferences {
        // Assuming ClockOverlayService.PREFS_NAME_FOR_ACTIVITY exists
        return context.getSharedPreferences(com.example.purramid.thepurramid.clock.ClockOverlayService.PREFS_NAME_FOR_ACTIVITY, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    @ScreenMaskPrefs // Use the qualifier
    fun provideScreenMaskPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences(com.example.purramid.thepurramid.screen_mask.ScreenMaskService.PREFS_NAME_FOR_ACTIVITY, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    @SpotlightPrefs // Use the qualifier
    fun provideSpotlightPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences(com.example.purramid.thepurramid.spotlight.SpotlightService.PREFS_NAME_FOR_ACTIVITY, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    @TimersPrefs
    fun provideTimersPreferences(@ApplicationContext context: Context): SharedPreferences {
         // Assuming TimersService.PREFS_NAME_FOR_ACTIVITY exists
        return context.getSharedPreferences(com.example.purramid.thepurramid.timers.TimersService.PREFS_NAME_FOR_ACTIVITY, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    @TrafficLightPrefs
    fun provideTrafficLightPreferences(@ApplicationContext context: Context): SharedPreferences {
        // Assuming TrafficLightService.PREFS_NAME_FOR_ACTIVITY exists
        return context.getSharedPreferences(com.example.purramid.thepurramid.traffic_light.TrafficLightService.PREFS_NAME_FOR_ACTIVITY, Context.MODE_PRIVATE)
    }
}