package com.example.purramid.thepurramid.spotlight

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.ActivitySpotlightBinding
import com.example.purramid.thepurramid.instance.InstanceManager
import com.example.purramid.thepurramid.spotlight.ui.SpotlightSettingsFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class SpotlightActivity : AppCompatActivity() {

    @Inject lateinit var instanceManager: InstanceManager

    private lateinit var binding: ActivitySpotlightBinding
    private val activityScope = CoroutineScope(Dispatchers.Main)

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
            activityScope.launch {
                handleDefaultLaunch()
            }
        }
    }

    private suspend fun handleDefaultLaunch() = withContext(Dispatchers.IO) {
        try {
            // Check for active instances
            val activeInstanceIds = instanceManager.getActiveInstanceIds(InstanceManager.SPOTLIGHT)

            withContext(Dispatchers.Main) {
                when {
                    activeInstanceIds.isNotEmpty() -> {
                        // There are active instances, show settings
                        Log.d(TAG, "Found ${activeInstanceIds.size} active Spotlight instances")
                        showSettingsFragment()
                    }
                    else -> {
                        // No active instances, check if we can create one
                        val nextInstanceId = instanceManager.getNextInstanceId(InstanceManager.SPOTLIGHT)
                        if (nextInstanceId != null) {
                            // Release the ID since the service will request it
                            instanceManager.releaseInstanceId(InstanceManager.SPOTLIGHT, nextInstanceId)

                            Log.d(TAG, "No active Spotlights, starting new service")
                            val serviceIntent = Intent(this@SpotlightActivity, SpotlightService::class.java).apply {
                                action = ACTION_START_SPOTLIGHT_SERVICE
                            }
                            ContextCompat.startForegroundService(this@SpotlightActivity, serviceIntent)
                            finish()
                        } else {
                            Log.w(TAG, "Maximum Spotlight instances reached")
                            // Could show a toast or dialog here
                            finish()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleDefaultLaunch", e)
            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }

    private fun showSettingsFragment() {
        if (supportFragmentManager.findFragmentByTag(SpotlightSettingsFragment.TAG) == null) {
            Log.d(TAG, "Showing Spotlight settings fragment.")
            supportFragmentManager.beginTransaction()
                .replace(R.id.spotlight_fragment_container, SpotlightSettingsFragment.newInstance(), SpotlightSettingsFragment.TAG)
                .commit()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent - Action: ${intent.action}")
        if (intent.action == ACTION_SHOW_SPOTLIGHT_SETTINGS) {
            showSettingsFragment()
        }
    }
}