// SpotlightBootReceiver.kt
package com.example.purramid.thepurramid.spotlight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.purramid.thepurramid.data.db.SpotlightDao
import com.example.purramid.thepurramid.instance.InstanceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores Spotlight service instances after device reboot.
 * Requires RECEIVE_BOOT_COMPLETED permission.
 */
@AndroidEntryPoint
class SpotlightBootReceiver : BroadcastReceiver() {

    @Inject lateinit var spotlightDao: SpotlightDao
    @Inject lateinit var instanceManager: InstanceManager

    companion object {
        private const val TAG = "SpotlightBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.d(TAG, "Boot completed, checking for Spotlight instances to restore")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get all active instances from database
                val activeInstances = spotlightDao.getActiveInstances()
                
                for (instance in activeInstances) {
                    // Check if instance has any openings
                    val openingsCount = spotlightDao.getOpeningCountForInstance(instance.instanceId)
                    
                    if (openingsCount > 0) {
                        Log.d(TAG, "Restoring Spotlight instance ${instance.instanceId} with $openingsCount openings")
                        
                        // Register with InstanceManager
                        instanceManager.registerExistingInstance(
                            InstanceManager.SPOTLIGHT,
                            instance.instanceId
                        )
                        
                        // Start service for this instance
                        val serviceIntent = Intent(context, SpotlightService::class.java).apply {
                            action = ACTION_START_SPOTLIGHT_SERVICE
                            putExtra(SpotlightService.KEY_INSTANCE_ID, instance.instanceId)
                        }
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } else {
                        Log.w(TAG, "Instance ${instance.instanceId} has no openings, skipping restoration")
                        // Clean up empty instance
                        spotlightDao.deleteInstance(instance.instanceId)
                    }
                }
                
                Log.d(TAG, "Spotlight restoration complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring Spotlight instances", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}