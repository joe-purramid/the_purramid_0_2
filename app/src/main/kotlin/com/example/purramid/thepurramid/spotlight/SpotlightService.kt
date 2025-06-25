// SpotlightService.kt
package com.example.purramid.thepurramid.spotlight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import com.example.purramid.thepurramid.MainActivity
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.SpotlightDao
import com.example.purramid.thepurramid.di.SpotlightPrefs
import com.example.purramid.thepurramid.instance.InstanceManager
import com.example.purramid.thepurramid.spotlight.SpotlightOpening
import com.example.purramid.thepurramid.spotlight.viewmodel.SpotlightViewModel
import com.example.purramid.thepurramid.spotlight.util.SpotlightMigrationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

// Define Actions
const val ACTION_START_SPOTLIGHT_SERVICE = "com.example.purramid.spotlight.ACTION_START_SERVICE"
const val ACTION_STOP_SPOTLIGHT_SERVICE = "com.example.purramid.spotlight.ACTION_STOP_SERVICE"
const val ACTION_ADD_NEW_SPOTLIGHT_OPENING = "com.example.purramid.spotlight.ACTION_ADD_NEW_OPENING"
const val ACTION_CLOSE_INSTANCE = "com.example.purramid.spotlight.ACTION_CLOSE_INSTANCE"

@AndroidEntryPoint
class SpotlightService : LifecycleService(), ViewModelStoreOwner {

    @Inject lateinit var windowManager: WindowManager
    @Inject lateinit var instanceManager: InstanceManager
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var spotlightDao: SpotlightDao
    @Inject @SpotlightPrefs lateinit var servicePrefs: SharedPreferences
    @Inject lateinit var migrationHelper: SpotlightMigrationHelper

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore = _viewModelStore

    private var instanceId: Int? = null
    private var spotlightViewModel: SpotlightViewModel? = null
    private var spotlightOverlayView: SpotlightView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var stateObserverJob: Job? = null
    private var isForeground = false

    companion object {
        private const val TAG = "SpotlightService"
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "SpotlightServiceChannel"
        const val MAX_OPENINGS_PER_OVERLAY = 4
        const val KEY_INSTANCE_ID = "spotlight_instance_id"
        const val PREFS_NAME_FOR_ACTIVITY = "spotlight_service_prefs"
        const val KEY_ACTIVE_COUNT_FOR_ACTIVITY = "active_spotlight_count"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()

        // Run migration check on service creation
        lifecycleScope.launch(Dispatchers.IO) {
            migrationHelper.migrateIfNeeded()
            handleServiceRecovery()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.d(TAG, "onStartCommand: Action: $action")

        when (action) {
            ACTION_START_SPOTLIGHT_SERVICE -> {
                if (instanceId == null) {
                    // Check if we should restore an existing instance
                    val existingInstanceId = intent?.getIntExtra(KEY_INSTANCE_ID, -1)?.takeIf { it > 0 }
                    if (existingInstanceId != null) {
                        // Restoring existing instance
                        restoreExistingInstance(existingInstanceId)
                    } else {
                        // Create new instance
                        initializeService(intent)
                    }
                }
            }
            ACTION_ADD_NEW_SPOTLIGHT_OPENING -> {
                if (instanceId == null) {
                    initializeService(intent)
                } else {
                    handleAddNewSpotlightOpening()
                }
            }
            ACTION_CLOSE_INSTANCE -> {
                val targetInstanceId = intent?.getIntExtra(KEY_INSTANCE_ID, -1)
                if (targetInstanceId == instanceId) {
                    stopService()
                }
            }
            ACTION_STOP_SPOTLIGHT_SERVICE -> {
                stopService()
            }
        }
        return START_STICKY // Ensures service restarts after being killed
    }

    private fun initializeService(intent: Intent?) {
        // Get or allocate instance ID
        instanceId = intent?.getIntExtra(KEY_INSTANCE_ID, -1)?.takeIf { it > 0 }
            ?: instanceManager.getNextInstanceId(InstanceManager.SPOTLIGHT)

        if (instanceId == null) {
            Log.e(TAG, "No available instance slots")
            stopSelf()
            return
        }

        Log.d(TAG, "Initializing service with instance ID: $instanceId")

        // Get screen dimensions
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Initialize ViewModel with instance ID and screen dimensions
        spotlightViewModel = ViewModelProvider(
            this,
            viewModelFactory
        ).get("spotlight_$instanceId", SpotlightViewModel::class.java)

        // Initialize the ViewModel with the instance data
        spotlightViewModel?.initialize(instanceId!!, screenWidth, screenHeight)

        // Create and attach overlay view
        createOverlayView()
        observeViewModelState()
        startForegroundServiceIfNeeded()
        updateActiveInstanceCount()
    }

    private fun handleServiceRecovery() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Check for orphaned instances
                val activeInstances = spotlightDao.getActiveInstances()
                for (instance in activeInstances) {
                    if (!instanceManager.getActiveInstanceIds(InstanceManager.SPOTLIGHT)
                            .contains(instance.instanceId)) {
                        // Found orphaned instance, re-register it
                        Log.d(TAG, "Re-registering orphaned instance ${instance.instanceId}")
                        instanceManager.registerExistingInstance(
                            InstanceManager.SPOTLIGHT,
                            instance.instanceId
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in service recovery", e)
            }
        }
    }

    private fun restoreExistingInstance(existingInstanceId: Int) {
        // Check if instance is already tracked by InstanceManager
        val isTracked = instanceManager.getActiveInstanceIds(InstanceManager.SPOTLIGHT)
            .contains(existingInstanceId)

        if (!isTracked) {
            // Re-register with instance manager
            val registered = instanceManager.registerExistingInstance(
                InstanceManager.SPOTLIGHT,
                existingInstanceId
            )
            if (!registered) {
                Log.e(TAG, "Failed to register existing instance $existingInstanceId")
                stopSelf()
                return
            }
        }

        instanceId = existingInstanceId
        Log.d(TAG, "Restoring existing instance ID: $instanceId")

        // Get screen dimensions
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Initialize ViewModel with restored instance ID
        spotlightViewModel = ViewModelProvider(
            this,
            viewModelFactory
        ).get("spotlight_$instanceId", SpotlightViewModel::class.java)

        // Initialize the ViewModel with the instance data
        spotlightViewModel?.initialize(instanceId!!, screenWidth, screenHeight)

        // Create and attach overlay view
        createOverlayView()
        observeViewModelState()
        startForegroundServiceIfNeeded()
        updateActiveInstanceCount()
    }

    private fun createOverlayView() {
        if (spotlightOverlayView != null) {
            Log.w(TAG, "Overlay view already exists")
            return
        }

        // Create layout params for fullscreen overlay
        overlayLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // Create the overlay view
        spotlightOverlayView = SpotlightView(this, null).apply {
            interactionListener = createInteractionListener()
        }

        try {
            windowManager.addView(spotlightOverlayView, overlayLayoutParams)
            Log.d(TAG, "Added overlay view to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view", e)
            stopService()
        }
    }

    private fun observeViewModelState() {
        stateObserverJob?.cancel()
        stateObserverJob = lifecycleScope.launch {
            spotlightViewModel?.uiState?.collectLatest { state ->
                Log.d(TAG, "State update: ${state.openings.size} openings, showControls=${state.showControls}")
                spotlightOverlayView?.updateState(state)
            }
        }
    }

    private fun createInteractionListener(): SpotlightView.SpotlightInteractionListener {
        return object : SpotlightView.SpotlightInteractionListener {
            override fun onOpeningMoved(openingId: Int, newX: Float, newY: Float) {
                // Handle per-opening movement
                spotlightViewModel?.updateOpeningFromDrag(openingId, newX, newY)
            }

            override fun onOpeningResized(opening: SpotlightOpening) {
                spotlightViewModel?.updateOpeningFromResize(opening)
            }

            override fun onOpeningShapeToggled(openingId: Int) {
                spotlightViewModel?.toggleOpeningShape(openingId)
            }

            override fun onOpeningLockToggled(openingId: Int) {
                spotlightViewModel?.toggleOpeningLock(openingId)
            }

            override fun onAllLocksToggled() {
                spotlightViewModel?.toggleAllLocks()
            }

            override fun onOpeningDeleted(openingId: Int) {
                lifecycleScope.launch {
                    val currentOpenings = spotlightViewModel?.uiState?.value?.openings ?: emptyList()

                    if (currentOpenings.size <= 1) {
                        // This is the last opening, close the entire service
                        Log.d(TAG, "Last opening deleted, stopping service")
                        stopService()
                    } else {
                        // Just delete this opening
                        spotlightViewModel?.deleteOpening(openingId)
                    }
                }
            }

            override fun onAddNewOpeningRequested() {
                handleAddNewSpotlightOpening()
            }

            override fun onControlsToggled() {
                val currentShowControls = spotlightViewModel?.uiState?.value?.showControls ?: true
                spotlightViewModel?.setShowControls(!currentShowControls)
            }

            override fun onSettingsRequested() {
                openSettingsActivity()
            }
        }
    }

    private fun handleAddNewSpotlightOpening() {
        val currentState = spotlightViewModel?.uiState?.value
        if (currentState == null) {
            Log.e(TAG, "ViewModel not initialized")
            return
        }

        if (currentState.openings.size >= MAX_OPENINGS_PER_OVERLAY) {
            Log.w(TAG, "Maximum openings reached")
            // Could show a toast or notification here
            return
        }

        val displayMetrics = resources.displayMetrics
        spotlightViewModel?.addOpening(displayMetrics.widthPixels, displayMetrics.heightPixels)
    }

    private fun openSettingsActivity() {
        val intent = Intent(this, SpotlightActivity::class.java).apply {
            action = SpotlightActivity.ACTION_SHOW_SPOTLIGHT_SETTINGS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(KEY_INSTANCE_ID, instanceId)
        }
        startActivity(intent)
    }

    private fun updateActiveInstanceCount() {
        val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.SPOTLIGHT)
        servicePrefs.edit().putInt(KEY_ACTIVE_COUNT_FOR_ACTIVITY, activeCount).apply()
    }

    private fun startForegroundServiceIfNeeded() {
        if (isForeground) return

        val notification = createNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
            Log.d(TAG, "Started foreground service")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.spotlight_title))
            .setContentText("Spotlight overlay is active")
            .setSmallIcon(R.drawable.ic_spotlight)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Spotlight Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun stopService() {
        Log.d(TAG, "Stopping service")

        // Cancel state observation
        stateObserverJob?.cancel()

        // Remove overlay view
        spotlightOverlayView?.let { view ->
            if (view.isAttachedToWindow) {
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing overlay view", e)
                }
            }
        }
        spotlightOverlayView = null

        // Release instance ID
        instanceId?.let {
            instanceManager.releaseInstanceId(InstanceManager.SPOTLIGHT, it)
            spotlightViewModel?.deactivateInstance()
        }

        // Update preferences
        updateActiveInstanceCount()

        // Stop foreground
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }

        // Clear ViewModelStore
        _viewModelStore.clear()

        // Stop self
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved - instance will be preserved")
        // State is automatically saved via Room, no action needed
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}