// RandomizersHostActivity.kt
package com.example.purramid.thepurramid.randomizers

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels // Import activityViewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.DEFAULT_SETTINGS_ID
import com.example.purramid.thepurramid.data.db.RandomizerDao
import com.example.purramid.thepurramid.data.db.RandomizerInstanceEntity
import com.example.purramid.thepurramid.data.db.SpinSettingsEntity
import com.example.purramid.thepurramid.databinding.ActivityRandomizersHostBinding
import com.example.purramid.thepurramid.instance.InstanceManager
import com.example.purramid.thepurramid.randomizers.viewmodel.RandomizerSettingsViewModel // Changed import
import com.example.purramid.thepurramid.randomizers.viewmodel.RandomizerViewModel // Keep for EXTRA_INSTANCE_ID
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class RandomizersHostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRandomizersHostBinding
    private var currentInstanceId: Int = 0
    private lateinit var navController: NavController

    // Use RandomizerSettingsViewModel to observe mode changes
    private val settingsViewModel: RandomizerSettingsViewModel by viewModels()

    @Inject lateinit var randomizerDao: RandomizerDao
    @Inject lateinit var instanceManager: InstanceManager

    companion object {
        private const val TAG = "RandomizersHostActivity"
        const val EXTRA_INSTANCE_ID = RandomizerViewModel.KEY_INSTANCE_ID // Consistent key
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRandomizersHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_randomizers) as NavHostFragment
        navController = navHostFragment.navController

        val instanceId = intent.getIntExtra(EXTRA_INSTANCE_ID, 0)
        if (instanceId == 0) {
            // Allocate a new instanceId
            val newInstanceId = instanceManager.getNextInstanceId(InstanceManager.RANDOMIZERS)
            if (newInstanceId == null) {
                Log.e(TAG, "No available instanceId. Maximum windows reached.")
                finish()
                return
            }
            currentInstanceId = newInstanceId
            intent.putExtra(EXTRA_INSTANCE_ID, newInstanceId)
            setIntent(intent)

            lifecycleScope.launch {
                createDefaultEntriesForNewInstance(newInstanceId)
                instanceManager.registerExistingInstance(InstanceManager.RANDOMIZERS, newInstanceId)
                Log.d(TAG, "HostActivity created AND DEFAULT DB ENTRIES ADDED for new instanceId: $newInstanceId")
                observeSettingsAndNavigate(newInstanceId)
            }
        } else {
            currentInstanceId = instanceId
            instanceManager.registerExistingInstance(InstanceManager.RANDOMIZERS, instanceId)
            Log.d(TAG, "HostActivity created and registered with existing instanceId: $instanceId")
            observeSettingsAndNavigate(instanceId)
        }
    }

    private suspend fun createDefaultEntriesForNewInstance(newInstanceId: Int) {
        withContext(Dispatchers.IO) {
            try {
                val globalDefaultSettings = randomizerDao.getDefaultSettings()
                val initialSettingsForInstance = globalDefaultSettings?.copy(
                    instanceId = newInstanceId,
                    mode = RandomizerMode.SPIN
                ) ?: SpinSettingsEntity(
                    instanceId = newInstanceId,
                    mode = RandomizerMode.SPIN
                )
                randomizerDao.saveSettings(initialSettingsForInstance)
                randomizerDao.saveInstance(RandomizerInstanceEntity(instanceId = newInstanceId))
                Log.d(TAG, "Default DB entries (settings and instance) created for new instance $newInstanceId")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating default DB entries for new instance $newInstanceId", e)
            }
        }
    }

    private fun observeSettingsAndNavigate(instanceId: Int) {
        settingsViewModel.settings.observe(this) { settings ->
            if (settings != null && settings.instanceId == instanceId) {
                Log.d(TAG, "Settings observed in HostActivity for instance ${settings.instanceId}. Mode: ${settings.mode}")
                navigateToModeFragment(settings.mode, instanceId)
            } else {
                Log.w(TAG, "Settings are null or instanceId mismatch for $instanceId. Cannot determine mode.")
                // Fallback or error display if settings can't be loaded
                if (navController.currentDestination?.id != R.id.randomizerMainFragment) {
                    // Optionally navigate to main fragment
                }
            }
        }
    }

    private fun navigateToModeFragment(mode: RandomizerMode, instanceId: Int) {
        val currentDestinationId = navController.currentDestination?.id
        val requiredDestinationId = when (mode) {
            RandomizerMode.SPIN -> R.id.randomizerMainFragment
            RandomizerMode.SLOTS -> R.id.slotsMainFragment
            RandomizerMode.DICE -> R.id.diceMainFragment
            RandomizerMode.COIN_FLIP -> R.id.coinFlipFragment
        }
        if (currentDestinationId == requiredDestinationId) {
            Log.d(TAG, "Already on the correct fragment for mode $mode. No navigation needed.")
            return
        }
        Log.d(TAG, "Navigating to mode: $mode for instance: $instanceId")
        val navOptions = androidx.navigation.NavOptions.Builder()
            .setPopUpTo(navController.graph.startDestinationId, true)
            .build()
        try {
            val args = Bundle().apply { putInt(RandomizerViewModel.KEY_INSTANCE_ID, instanceId) }
            navController.navigate(requiredDestinationId, args, navOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Navigation failed for mode $mode: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instanceManager.releaseInstanceId(InstanceManager.RANDOMIZERS, currentInstanceId)
        Log.d(TAG, "HostActivity destroyed and released instanceId: $currentInstanceId")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called for HostActivity. New Intent Action: ${intent?.action}")
        val newInstanceId = intent?.getIntExtra(EXTRA_INSTANCE_ID, 0) ?: 0
        if (newInstanceId != 0 && newInstanceId != currentInstanceId) {
            instanceManager.releaseInstanceId(InstanceManager.RANDOMIZERS, currentInstanceId)
            currentInstanceId = newInstanceId
            instanceManager.registerExistingInstance(InstanceManager.RANDOMIZERS, newInstanceId)
            setIntent(intent)
            observeSettingsAndNavigate(newInstanceId)
        } else if (newInstanceId != 0) {
            observeSettingsAndNavigate(newInstanceId)
        }
    }
}