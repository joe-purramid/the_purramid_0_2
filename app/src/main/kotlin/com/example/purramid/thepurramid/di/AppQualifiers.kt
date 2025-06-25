package com.example.purramid.thepurramid.di

import javax.inject.Qualifier

// Define the qualifier annotation
@Qualifier
@Retention(AnnotationRetention.BINARY) // Standard retention for qualifiers
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ClockPrefs // For ClockService SharedPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ScreenMaskPrefs

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SpotlightPrefs // Assuming you'll need one for Spotlight too

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TimersPrefs // For TimersService SharedPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TrafficLightPrefs // For TrafficLightService SharedPreferences