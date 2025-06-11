// SpotlightActivity.kt
package com.example.purramid.thepurramid.spotlight

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.ActivitySpotlightBinding // To host fragment
import com.example.purramid.thepurramid.spotlight.SpotlightService
import com.example.purramid.thepurramid.spotlight.ui.SpotlightSettingsFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SpotlightActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpotlightBinding

    companion object {
        private const val TAG = "SpotlightActivity"
        const val ACTION_SHOW_SPOTLIGHT_SETTINGS = "com.example.purramid.spotlight.ACTION_SHOW_SETTINGS"
   }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpotlightBinding.inflate(layoutInflater)
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
        if (currentIntent?.action == ACTION_SHOW_SPOTLIGHT_SETTINGS) {
            showSettingsFragment()
        } else {
            // Default launch path if not showing settings
            // Reference the constants directly from SpotlightService's companion object
            val prefs = getSharedPreferences(SpotlightService.PREFS_NAME_FOR_ACTIVITY, Context.MODE_PRIVATE)
            val activeCount = prefs.getInt(SpotlightService.KEY_ACTIVE_COUNT_FOR_ACTIVITY, 0)

            if (activeCount > 0) {
                Log.d(TAG, "Spotlights active ($activeCount) for Purramid, launching settings fragment.")
                showSettingsFragment()
            } else {
                Log.d(TAG, "No active Spotlights for Purramid, requesting service to add a new one.")
                val serviceIntent = Intent(this, SpotlightService::class.java).apply {
                    action = ACTION_ADD_NEW_SPOTLIGHT_INSTANCE
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                // It's generally better to finish the activity after starting the service
                // if the activity's only purpose was to trigger this.
                finish()
            }
        }
    }

    private fun showSettingsFragment() {
        if (supportFragmentManager.findFragmentByTag(SpotlightSettingsFragment.TAG) == null) {
            Log.d(TAG, "Showing Spotlight settings fragment.")
            supportFragmentManager.beginTransaction()
                .replace(R.id.spotlight_fragment_container, SpotlightSettingsFragment.newInstance())
                .commit()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent - Action: ${intent?.action}")
        if (intent?.action == ACTION_SHOW_SPOTLIGHT_SETTINGS) {
            showSettingsFragment()
        }
    }
}