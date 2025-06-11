// ClockActivity.kt
package com.example.purramid.thepurramid.clock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ClockActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ClockActivity"
        // Use constants from Service for SharedPreferences & actions
        const val PREFS_NAME = ClockOverlayService.PREFS_NAME_FOR_ACTIVITY
        const val KEY_ACTIVE_COUNT = ClockOverlayService.KEY_ACTIVE_COUNT_FOR_ACTIVITY
        // Action to show settings can be defined here or in ClockSettingsActivity
        const val ACTION_SHOW_CLOCK_SETTINGS = "com.example.purramid.clock.ACTION_SHOW_SETTINGS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This activity might not need its own layout if it only launches service/settings
        Log.d(TAG, "onCreate - Intent Action: ${intent.action}")

        // Check if launched to show settings (e.g., from service overlay)
        if (intent.action == ACTION_SHOW_CLOCK_SETTINGS) {
            val clockIdForSettings = intent.getIntExtra(ClockOverlayService.EXTRA_CLOCK_ID, 0)
            launchSettings(clockIdForSettings)
            // Don't finish if showing settings
        } else {
            // Default launch path (e.g., from MainActivity)
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val activeCount = prefs.getInt(KEY_ACTIVE_COUNT, 0)

            if (activeCount > 0) {
                Log.d(TAG, "Clocks active ($activeCount), launching ClockSettingsActivity.")
                launchSettings(0) // Pass 0 or a "primary" ID if settings needs context
            } else {
                Log.d(TAG, "No active Clocks, requesting service to add a new one.")
                val serviceIntent = Intent(this, ClockOverlayService::class.java).apply {
                    action = ACTION_ADD_NEW_CLOCK // This action tells service to generate ID
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
            finish() // Finish after launching service or settings
        }
    }

    private fun launchSettings(clockId: Int) {
        val settingsIntent = Intent(this, ClockSettingsActivity::class.java).apply {
            // If settings needs to configure a specific clock, pass its ID
            if (clockId > 0) {
                putExtra(ClockOverlayService.EXTRA_CLOCK_ID, clockId)
            }
            // Flags to bring existing settings to front or start new if not running
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(settingsIntent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent
        Log.d(TAG, "onNewIntent - Action: ${intent?.action}")
        if (intent?.action == ACTION_SHOW_CLOCK_SETTINGS) {
            val clockIdForSettings = intent.getIntExtra(ClockOverlayService.EXTRA_CLOCK_ID, 0)
            launchSettings(clockIdForSettings)
        }
    }
}