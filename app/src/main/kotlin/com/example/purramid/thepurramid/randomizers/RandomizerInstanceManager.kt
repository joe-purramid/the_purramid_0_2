// RandomizerInstanceManager.kt
package com.example.purramid.thepurramid.randomizers

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RandomizerInstanceManager {

    private const val TAG = "RandomizerInstanceManager"
    private val activeInstances = mutableMapOf<Int, UUID>() // instanceId to UUID
    private val maxInstances = 4

    /** Registers an instance as active when its Activity is created. */
    fun registerInstance(instanceId: Int, uuid: UUID = UUID.randomUUID()) {
        activeInstances[instanceId] = uuid
        Log.d(TAG, "Registered instance: $instanceId ($uuid). Active count: ${activeInstances.size}")
    }

    /** Unregisters an instance when its Activity is destroyed. */
    fun unregisterInstance(instanceId: Int) {
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
        return activeInstances.size == 1 && activeInstances.containsValue(instanceId)
    }

    /** Gets the current count of active Randomizer instances. */
    fun getActiveInstanceCount(): Int = activeInstances.size

    /** Gets a snapshot of the currently active instance IDs. */
    fun getActiveInstanceIds(): Set<Int> = activeInstances.keys

    /** Clears all registered instances (e.g., if the app is fully closing or for a reset). */
    fun clearAllInstances() {
        activeInstances.clear()
        Log.d(TAG, "Cleared all registered instances.")
    }

    fun getNextInstanceId(): Int? {
        for (i in 1..maxInstances) {
            if (!activeInstances.containsKey(i)) return i
        }
        return null
    }

    fun getUuidForInstance(instanceId: Int): UUID? = activeInstances[instanceId]
}