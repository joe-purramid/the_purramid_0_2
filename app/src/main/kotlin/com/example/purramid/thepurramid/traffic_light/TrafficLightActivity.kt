// TrafficLightActivity.kt
package com.example.purramid.thepurramid.traffic_light

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.ActivityTrafficLightBinding
import com.example.purramid.thepurramid.traffic_light.viewmodel.TrafficLightViewModel // For KEY_INSTANCE_ID
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TrafficLightActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrafficLightBinding

    companion object {
        private const val TAG = "TrafficLightActivity"
        const val ACTION_SHOW_SETTINGS = "com.example.purramid.traffic_light.ACTION_SHOW_SETTINGS"
        // Use constants from Service for SharedPreferences
        const val PREFS_NAME = TrafficLightService.PREFS_NAME_FOR_ACTIVITY
        const val KEY_ACTIVE_COUNT = TrafficLightService.KEY_ACTIVE_COUNT_FOR_ACTIVITY
        const val EXTRA_INSTANCE_ID = TrafficLightViewModel.KEY_INSTANCE_ID
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrafficLightBinding.inflate(layoutInflater)
        setContentView(binding.root) // Must contain R.id.traffic_light_fragment_container
        Log.d(TAG, "onCreate - Intent Action: ${intent.action}")

        val instanceIdForFragment = intent.getIntExtra(EXTRA_INSTANCE_ID, 0)

        if (intent.action == ACTION_SHOW_SETTINGS) {
            showSettingsFragment(instanceIdForFragment) // Pass 0 if general, or specific ID
        } else {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val activeCount = prefs.getInt(KEY_ACTIVE_COUNT, 0)

            if (activeCount > 0) {
                Log.d(TAG, "Traffic Lights active ($activeCount), launching settings fragment.")
                showSettingsFragment(0) // Show general settings
            } else {
                Log.d(TAG, "No active Traffic Lights, requesting service to add a new one.")
                val serviceIntent = Intent(this, TrafficLightService::class.java).apply {
                    action = ACTION_ADD_NEW_TRAFFIC_LIGHT_INSTANCE
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                finish()
            }
        }
    }

    private fun showSettingsFragment(instanceId: Int) {
        if (supportFragmentManager.findFragmentByTag(TrafficLightSettingsFragment.TAG) == null) {
            Log.d(TAG, "Showing Traffic Light settings fragment for instance (or general): $instanceId")
            val fragment = TrafficLightSettingsFragment.newInstance().apply {
                // If your settings fragment needs to be aware of a specific instance ID
                // arguments = Bundle().apply { putInt(TrafficLightViewModel.KEY_INSTANCE_ID, instanceId) }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.traffic_light_fragment_container, fragment, TrafficLightSettingsFragment.TAG)
                .commit()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent - Action: ${intent?.action}")
        if (intent?.action == ACTION_SHOW_SETTINGS) {
            val instanceIdForFragment = intent.getIntExtra(EXTRA_INSTANCE_ID, 0)
            showSettingsFragment(instanceIdForFragment)
        }
    }
}