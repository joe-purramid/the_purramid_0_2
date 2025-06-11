// RandomizerInstanceManager.kt
package com.example.purramid.thepurramid.randomizers

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RandomizerInstanceManager {

    private const val TAG = "RandomizerInstanceManager"
    private val activeInstances = ConcurrentHashMap<UUID, Boolean>() // Store instanceId to a boolean (true if active)

    /** Registers an instance as active when its Activity is created. */
    fun registerInstance(instanceId: UUID) {
        activeInstances[instanceId] = true
        Log.d(TAG, "Registered instance: $instanceId. Active count: ${activeInstances.size}")
    }

    /** Unregisters an instance when its Activity is destroyed. */
    fun unregisterInstance(instanceId: UUID) {
        activeInstances.remove(instanceId)
        Log.d(TAG, "Unregistered instance: $instanceId. Active count: ${activeInstances.size}")
    }

    /**
     * Checks if the given instance ID was the only one registered before it's potentially unregistered.
     * Note: This is more for a service's default settings save. For Activities, closing one
     * doesn't automatically mean the app is saving global defaults unless it's the truly last one.
     */
    fun isLastInstance(instanceId: UUID): Boolean {
        // This logic might need refinement based on when it's called relative to unregisterInstance
        return activeInstances.size == 1 && activeInstances.containsKey(instanceId)
    }

    /** Gets the current count of active Randomizer instances. */
    fun getActiveInstanceCount(): Int {
        return activeInstances.size
    }

    /** Gets a snapshot of the currently active instance IDs. */
    fun getActiveInstanceIds(): Set<UUID> {
        return activeInstances.keys.toSet() // Return a copy
    }

    /** Clears all registered instances (e.g., if the app is fully closing or for a reset). */
    fun clearAllInstances() {
        activeInstances.clear()
        Log.d(TAG, "Cleared all registered instances.")
    }
}