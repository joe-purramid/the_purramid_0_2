// SpotlightMigrationHelper.kt
package com.example.purramid.thepurramid.spotlight.util

import android.util.Log
import com.example.purramid.thepurramid.data.db.SpotlightDao
import com.example.purramid.thepurramid.data.db.SpotlightInstanceEntity
import com.example.purramid.thepurramid.data.db.SpotlightOpeningEntity
import com.example.purramid.thepurramid.instance.InstanceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Helper class to migrate from the old spotlight_state table to the new
 * spotlight_instances and spotlight_openings structure.
 */
class SpotlightMigrationHelper @Inject constructor(
    private val spotlightDao: SpotlightDao,
    private val instanceManager: InstanceManager
) {
    companion object {
        private const val TAG = "SpotlightMigration"
    }

    /**
     * Checks if migration is needed and performs it if necessary.
     * This should be called once during app startup or service initialization.
     */
    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        try {
            // Check if we have any active instances
            val activeInstances = spotlightDao.getActiveInstances()
            
            if (activeInstances.isEmpty()) {
                // No instances exist, this might be a fresh install or we need to migrate
                Log.d(TAG, "No active instances found. Checking for legacy data...")
                
                // The migration in the database should have already moved the data,
                // but we need to ensure instance tracking is set up correctly
                val instanceCount = spotlightDao.getActiveInstanceCount()
                if (instanceCount == 0) {
                    // Check if we have openings without proper instance setup
                    val orphanedOpenings = spotlightDao.getOpeningsForInstance(1)
                    if (orphanedOpenings.isNotEmpty()) {
                        Log.d(TAG, "Found ${orphanedOpenings.size} orphaned openings. Setting up instance.")
                        
                        // Register the instance with InstanceManager
                        val registered = instanceManager.registerExistingInstance(
                            InstanceManager.SPOTLIGHT, 
                            1
                        )
                        
                        if (registered) {
                            // Create the instance entity if it doesn't exist
                            val instance = SpotlightInstanceEntity(
                                instanceId = 1,
                                isActive = true
                            )
                            spotlightDao.insertOrUpdateInstance(instance)
                            Log.d(TAG, "Migration completed. Instance 1 created with ${orphanedOpenings.size} openings.")
                        }
                    }
                }
            } else {
                Log.d(TAG, "Active instances found. No migration needed.")
                
                // Register all active instances with InstanceManager
                activeInstances.forEach { instance ->
                    instanceManager.registerExistingInstance(
                        InstanceManager.SPOTLIGHT,
                        instance.instanceId
                    )
                }
            }
            
            // Clean up legacy table if it exists
            try {
                spotlightDao.clearLegacyTable()
                Log.d(TAG, "Legacy table cleared.")
            } catch (e: Exception) {
                // Table might not exist anymore, which is fine
                Log.d(TAG, "Legacy table cleanup skipped: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during migration check", e)
        }
    }
}