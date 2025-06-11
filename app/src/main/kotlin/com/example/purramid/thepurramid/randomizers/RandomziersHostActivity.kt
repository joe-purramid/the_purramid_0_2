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
    private var currentInstanceId: UUID? = null
    private lateinit var navController: NavController

    // Use RandomizerSettingsViewModel to observe mode changes
    private val settingsViewModel: RandomizerSettingsViewModel by viewModels()

    @Inject
    lateinit var randomizerDao: RandomizerDao

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

        val instanceIdString = intent.getStringExtra(EXTRA_INSTANCE_ID)
        if (instanceIdString == null) {
            Log.d(TAG, "instanceId is null in Intent. Assuming first instance, creating defaults.")
            val newInstanceId = UUID.randomUUID()
            currentInstanceId = newInstanceId
            intent.putExtra(EXTRA_INSTANCE_ID, newInstanceId.toString()) // Update intent for VMs
            setIntent(intent) // Important for SavedStateHandle

            lifecycleScope.launch {
                createDefaultEntriesForNewInstance(newInstanceId)
                RandomizerInstanceManager.registerInstance(newInstanceId)
                Log.d(TAG, "HostActivity created AND DEFAULT DB ENTRIES ADDED for new instanceId: $newInstanceId")
                // Observe settings *after* ensuring instanceId is processed by SavedStateHandle
                observeSettingsAndNavigate(newInstanceId.toString())
            }
        } else {
            try {
                currentInstanceId = UUID.fromString(instanceIdString)
                currentInstanceId?.let {
                    RandomizerInstanceManager.registerInstance(it)
                    Log.d(TAG, "HostActivity created and registered with existing instanceId: $it")
                    observeSettingsAndNavigate(it.toString())
                } ?: run {
                    Log.e(TAG, "Parsed UUID is null unexpectedly. Finishing.")
                    finish()
                    return
                }
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid UUID format received: $instanceIdString. Finishing activity.", e)
                finish()
                return
            }
        }
    }

    private suspend fun createDefaultEntriesForNewInstance(newInstanceId: UUID) {
        withContext(Dispatchers.IO) {
            try {
                // Try to get global default settings
                val globalDefaultSettings = randomizerDao.getDefaultSettings(DEFAULT_SETTINGS_ID)
                val initialSettingsForInstance = globalDefaultSettings?.copy(
                    instanceId = newInstanceId,
                    // Reset any fields that should be unique or start fresh for a new instance
                    // e.g., if currentListId was part of global defaults, maybe nullify it here
                    // For now, we assume SpinSettingsEntity constructor defaults are fine for a new instance.
                    mode = RandomizerMode.SPIN // Default new instances to Spin
                ) ?: SpinSettingsEntity(
                    instanceId = newInstanceId,
                    mode = RandomizerMode.SPIN // Explicitly default to SPIN here too
                )

                randomizerDao.saveSettings(initialSettingsForInstance)
                randomizerDao.saveInstance(RandomizerInstanceEntity(instanceId = newInstanceId))
                Log.d(TAG, "Default DB entries (settings and instance) created for new instance $newInstanceId")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating default DB entries for new instance $newInstanceId", e)
            }
        }
    }

    private fun observeSettingsAndNavigate(instanceIdString: String) {
        // The RandomizerSettingsViewModel is already scoped and will load settings for this instanceId.
        // We observe its 'settings' LiveData to determine which fragment to show.
        settingsViewModel.settings.observe(this) { settings ->
            if (settings != null) {
                Log.d(TAG, "Settings observed in HostActivity for instance ${settings.instanceId}. Mode: ${settings.mode}")
                navigateToModeFragment(settings.mode, instanceIdString)
            } else {
                Log.w(TAG, "Settings are null for instance $instanceIdString. Cannot determine mode.")
                // Fallback or error display if settings can't be loaded
                // For now, if settings are null, it might mean an error handled by ViewModel or initial load.
                // We might default to a placeholder or the main RandomizerFragment if that's the start destination.
                // If startDestination is RandomizerMainFragment, and it expects settings, this could be an issue.
                // Let's assume RandomizerMainFragment can handle null settings or shows a loading state.
                if (navController.currentDestination?.id != R.id.randomizerMainFragment) {
                    // If settings are null and we are not on main, perhaps navigate to main as a fallback.
                    // This depends on how robust RandomizerMainFragment is.
                    // For now, we only navigate if settings are non-null.
                }
            }
        }
    }

    private fun navigateToModeFragment(mode: RandomizerMode, instanceIdStr: String) {
        // Prevent re-navigation if already on the correct destination for the current mode
        val currentDestinationId = navController.currentDestination?.id
        val requiredDestinationId = when (mode) {
            RandomizerMode.SPIN -> R.id.randomizerMainFragment // Assuming SpinDialView is part of RandomizerMainFragment
            RandomizerMode.SLOTS -> R.id.slotsMainFragment
            RandomizerMode.DICE -> R.id.diceMainFragment
            RandomizerMode.COIN_FLIP -> R.id.coinFlipFragment
        }

        if (currentDestinationId == requiredDestinationId) {
            Log.d(TAG, "Already on the correct fragment for mode $mode. No navigation needed.")
            return
        }

        Log.d(TAG, "Navigating to mode: $mode for instance: $instanceIdStr")

        // Clear back stack up to the graph's start destination to prevent deep stacks when switching modes.
        // This makes each mode feel like a top-level view within this Randomizer instance.
        val navOptions = androidx.navigation.NavOptions.Builder()
            .setPopUpTo(navController.graph.startDestinationId, true) // Pop up to start, inclusive
            .build()

        try {
            when (mode) {
                RandomizerMode.SPIN -> {
                    // RandomizerMainFragment is the start destination, it handles Spin mode.
                    // If navigating explicitly, ensure args are handled or use a specific action.
                    // For now, if mode is SPIN, we assume we should be at the start destination.
                    // If not already there, navigate. This might be redundant if it's already the start.
                    if (currentDestinationId != R.id.randomizerMainFragment) {
                        navController.navigate(R.id.randomizerMainFragment, Bundle().apply {
                            putString(RandomizerViewModel.KEY_INSTANCE_ID, instanceIdStr)
                        }, navOptions)
                    }
                }
                RandomizerMode.SLOTS -> {
                    val args = Bundle().apply { putString(RandomizerViewModel.KEY_INSTANCE_ID, instanceIdStr) }
                    navController.navigate(R.id.slotsMainFragment, args, navOptions)
                }
                RandomizerMode.DICE -> {
                    val args = Bundle().apply { putString(RandomizerViewModel.KEY_INSTANCE_ID, instanceIdStr) }
                    navController.navigate(R.id.diceMainFragment, args, navOptions)
                }
                RandomizerMode.COIN_FLIP -> {
                    val args = Bundle().apply { putString(RandomizerViewModel.KEY_INSTANCE_ID, instanceIdStr) }
                    navController.navigate(R.id.coinFlipFragment, args, navOptions)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Navigation failed for mode $mode: ${e.message}")
            // Fallback or error message
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        currentInstanceId?.let {
            RandomizerInstanceManager.unregisterInstance(it)
            Log.d(TAG, "HostActivity destroyed and unregistered instanceId: $it")
            // Logic to save default settings if this was the last instance is now in RandomizerSettingsViewModel
        }
    }

    // If this activity can be relaunched with a new intent (e.g., from notification)
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called for HostActivity. New Intent Action: ${intent?.action}")
        // Re-evaluate navigation based on the new intent's instanceId or other parameters
        val newInstanceIdString = intent?.getStringExtra(EXTRA_INSTANCE_ID)
        if (newInstanceIdString != null && newInstanceIdString != currentInstanceId?.toString()) {
            Log.d(TAG, "HostActivity received new instanceId via onNewIntent: $newInstanceIdString")
            // Unregister old, register new
            currentInstanceId?.let { RandomizerInstanceManager.unregisterInstance(it) }
            try {
                currentInstanceId = UUID.fromString(newInstanceIdString)
                currentInstanceId?.let {
                    RandomizerInstanceManager.registerInstance(it)
                    // Update the activity's intent to ensure SavedStateHandle gets the new ID for ViewModels
                    setIntent(intent)
                    // Trigger re-observation or re-navigation
                    observeSettingsAndNavigate(newInstanceIdString)
                }
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid UUID in onNewIntent: $newInstanceIdString")
                finish() // Invalid state
            }
        } else if (newInstanceIdString != null) {
            // Same instance, but could be a re-launch. Ensure navigation is correct.
            observeSettingsAndNavigate(newInstanceIdString)
        }
    }
}