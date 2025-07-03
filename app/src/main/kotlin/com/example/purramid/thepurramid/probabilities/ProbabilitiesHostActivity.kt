package com.example.purramid.thepurramid.probabilities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.instance.InstanceManager
import com.example.purramid.thepurramid.probabilities.viewmodel.ProbabilitiesSettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ProbabilitiesHostActivity : AppCompatActivity() {
    private lateinit var navController: NavController
    private var currentInstanceId: Int = 0
    private val settingsViewModel: ProbabilitiesSettingsViewModel by viewModels()

    @Inject lateinit var instanceManager: InstanceManager

    companion object {
        private const val TAG = "ProbabilitiesHostActivity"
        const val EXTRA_INSTANCE_ID = "probabilities_instance_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_probabilities_host)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_probabilities) as NavHostFragment
        navController = navHostFragment.navController

        val instanceId = intent.getIntExtra(EXTRA_INSTANCE_ID, 0)
        if (instanceId == 0) {
            // Allocate a new instanceId
            val newInstanceId = instanceManager.getNextInstanceId(InstanceManager.PROBABILITIES)
            if (newInstanceId == null) {
                Log.e(TAG, "No available instanceId. Maximum windows reached.")
                finish()
                return
            }
            currentInstanceId = newInstanceId
            intent.putExtra(EXTRA_INSTANCE_ID, newInstanceId)
            setIntent(intent)
            lifecycleScope.launch {
                instanceManager.registerExistingInstance(InstanceManager.PROBABILITIES, newInstanceId)
                observeSettingsAndNavigate(newInstanceId)
            }
        } else {
            currentInstanceId = instanceId
            lifecycleScope.launch {
                instanceManager.registerExistingInstance(InstanceManager.PROBABILITIES, instanceId)
                observeSettingsAndNavigate(instanceId)
            }
        }
    }

    private fun observeSettingsAndNavigate(instanceId: Int) {
        settingsViewModel.settings.observe(this) { settings ->
            if (settings != null && settings.instanceId == instanceId) {
                Log.d(TAG, "Settings observed in ProbabilitiesHostActivity for instance ${settings.instanceId}. Mode: ${settings.mode}")
                navigateToModeFragment(settings.mode, instanceId)
            } else {
                Log.w(TAG, "Settings are null or instanceId mismatch for $instanceId. Cannot determine mode.")
            }
        }
    }

    private fun navigateToModeFragment(mode: ProbabilitiesMode, instanceId: Int) {
        val currentDestinationId = navController.currentDestination?.id
        val requiredDestinationId = when (mode) {
            ProbabilitiesMode.DICE -> R.id.diceMainFragment
            ProbabilitiesMode.COIN_FLIP -> R.id.coinFlipFragment
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
            val args = Bundle().apply { putInt(EXTRA_INSTANCE_ID, instanceId) }
            navController.navigate(requiredDestinationId, args, navOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Navigation failed for mode $mode: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instanceManager.releaseInstanceId(InstanceManager.PROBABILITIES, currentInstanceId)
        Log.d(TAG, "ProbabilitiesHostActivity destroyed and released instanceId: $currentInstanceId")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called for ProbabilitiesHostActivity. New Intent Action: ${intent?.action}")
        val newInstanceId = intent?.getIntExtra(EXTRA_INSTANCE_ID, 0) ?: 0
        if (newInstanceId != 0 && newInstanceId != currentInstanceId) {
            instanceManager.releaseInstanceId(InstanceManager.PROBABILITIES, currentInstanceId)
            currentInstanceId = newInstanceId
            instanceManager.registerExistingInstance(InstanceManager.PROBABILITIES, newInstanceId)
            setIntent(intent)
            observeSettingsAndNavigate(newInstanceId)
        } else if (newInstanceId != 0) {
            observeSettingsAndNavigate(newInstanceId)
        }
    }
} 