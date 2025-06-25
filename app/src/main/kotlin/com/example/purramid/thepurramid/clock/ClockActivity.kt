// ClockActivity.kt
package com.example.purramid.thepurramid.clock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.ActivityClockBinding
import com.example.purramid.thepurramid.clock.ui.ClockSettingsFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ClockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClockBinding

    companion object {
        private const val TAG = "ClockActivity"
        // Use constants from Service for SharedPreferences & actions
        const val PREFS_NAME = ClockOverlayService.PREFS_NAME_FOR_ACTIVITY
        const val KEY_ACTIVE_COUNT = ClockOverlayService.KEY_ACTIVE_COUNT_FOR_ACTIVITY
        // Action to show settings can be defined here or in ClockSettingsFragment
        const val ACTION_SHOW_CLOCK_SETTINGS = "com.example.purramid.clock.ACTION_SHOW_SETTINGS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate - Intent Action: ${intent.action}")

        // Handle the initial intent that started the activity
        handleIntent(intent)
    }

    /**
     * Handles the logic based on the activity's current intent.
     * This can be called from onCreate or onNewIntent.
     */
    private fun handleIntent(currentIntent: Intent?) {
        Log.d(TAG, "handleIntent - Action: ${currentIntent?.action}")
        if (currentIntent?.action == ACTION_SHOW_CLOCK_SETTINGS) {
            val clockIdForSettings = currentIntent.getIntExtra(ClockOverlayService.EXTRA_CLOCK_ID, 0)
            showSettingsFragment(clockIdForSettings)
        } else {
            // Default launch path if not showing settings
            handleDefaultLaunch()
        }
    }

    private fun handleDefaultLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeCount = prefs.getInt(KEY_ACTIVE_COUNT, 0)

        if (activeCount > 0) {
            Log.d(TAG, "Clocks active ($activeCount), showing ClockSettingsFragment.")
            showSettingsFragment(0) // Pass 0 or a "primary" ID if settings needs context
        } else {
            Log.d(TAG, "No active Clocks, requesting service to add a new one.")
            val serviceIntent = Intent(this, ClockOverlayService::class.java).apply {
                action = ACTION_ADD_NEW_CLOCK // This action tells service to generate ID
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            finish() // Finish after launching service
        }
    }

    private fun showSettingsFragment(clockId: Int) {
        if (supportFragmentManager.findFragmentByTag(ClockSettingsFragment.TAG_FRAGMENT) == null) {
            Log.d(TAG, "Showing Clock settings fragment for clock ID: $clockId")
            supportFragmentManager.beginTransaction()
                .replace(R.id.clock_fragment_container, ClockSettingsFragment.newInstance(clockId), ClockSettingsFragment.TAG_FRAGMENT)
                .commit()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent
        Log.d(TAG, "onNewIntent - Action: ${intent?.action}")
        if (intent?.action == ACTION_SHOW_CLOCK_SETTINGS) {
            val clockIdForSettings = intent.getIntExtra(ClockOverlayService.EXTRA_CLOCK_ID, 0)
            showSettingsFragment(clockIdForSettings)
        }
    }
}