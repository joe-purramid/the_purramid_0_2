// ScreenServiceMask.kt
package com.example.purramid.thepurramid.screen_mask

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast // Retain for cases where Snackbar isn't feasible from service
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.ScreenMaskDao // For restoring state
import com.example.purramid.thepurramid.di.HiltViewModelFactory // Assuming Hilt Factory for custom creation
import com.example.purramid.thepurramid.screen_mask.ui.MaskView
import com.example.purramid.thepurramid.screen_mask.viewmodel.ScreenMaskViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

// Actions for ScreenMaskService
const val ACTION_START_SCREEN_MASK = "com.example.purramid.screen_mask.ACTION_START"
const val ACTION_STOP_SCREEN_MASK_SERVICE = "com.example.purramid.screen_mask.ACTION_STOP_SERVICE"
const val ACTION_ADD_NEW_MASK_INSTANCE = "com.example.purramid.screen_mask.ACTION_ADD_NEW_INSTANCE"
const val ACTION_REQUEST_IMAGE_CHOOSER = "com.example.purramid.screen_mask.ACTION_REQUEST_IMAGE_CHOOSER" // Service sends to Activity
const val ACTION_BILLBOARD_IMAGE_SELECTED = "com.example.purramid.screen_mask.ACTION_BILLBOARD_IMAGE_SELECTED" // Activity sends to Service
const val EXTRA_MASK_INSTANCE_ID = ScreenMaskViewModel.KEY_INSTANCE_ID // From ViewModel
const val EXTRA_IMAGE_URI = "com.example.purramid.screen_mask.EXTRA_IMAGE_URI"

@AndroidEntryPoint
class ScreenMaskService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    @Inject lateinit var windowManager: WindowManager
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory // Hilt provides default factory
    @Inject lateinit var screenMaskDao: ScreenMaskDao // Inject DAO for state restoration
    @Inject @ScreenMaskPrefs lateinit var servicePrefs: SharedPreferences

    private val _viewModelStore = ViewModelStore()
    override fun getViewModelStore(): ViewModelStore = _viewModelStore

    // For SavedStateRegistryOwner
    private lateinit var savedStateRegistryController: SavedStateRegistryController

    override fun getSavedStateRegistry(): SavedStateRegistry { // Implement method
        return savedStateRegistryController.savedStateRegistry
    }

    private val activeMaskViewModels = ConcurrentHashMap<Int, ScreenMaskViewModel>()
    private val activeMaskViews = ConcurrentHashMap<Int, MaskView>()
    private val maskLayoutParams = ConcurrentHashMap<Int, WindowManager.LayoutParams>()
    private val stateObserverJobs = ConcurrentHashMap<Int, Job>()

    private val instanceIdCounter = AtomicInteger(0)
    private var isForeground = false
    private var imageChooserTargetInstanceId: Int? = null

    companion object {
        private const val TAG = "ScreenMaskService"
        private const val NOTIFICATION_ID = 6
        private const val CHANNEL_ID = "ScreenMaskServiceChannel"
        const val MAX_MASKS = 4 // Shared constant for max masks

        // Define the actual string literals here
        const val PREFS_NAME = "com.example.purramid.thepurramid.screen_mask.APP_PREFERENCES"
        const val KEY_ACTIVE_COUNT = "SCREEN_MASK_ACTIVE_INSTANCE_COUNT"
        const val KEY_LAST_INSTANCE_ID = "last_instance_id_screenmask"
    }

    override fun onCreate() {
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)

        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        loadLastInstanceId()
        loadAndRestoreMaskStates() // Attempt to restore any previously active masks
    }

    private fun loadLastInstanceId() {
        val lastId = servicePrefs.getInt(KEY_LAST_INSTANCE_ID, 0)
        instanceIdCounter.set(lastId)
        Log.d(TAG, "Loaded last instance ID for ScreenMask: $lastId")
    }

    private fun saveLastInstanceId() {
        servicePrefs.edit().putInt(KEY_LAST_INSTANCE_ID, instanceIdCounter.get()).apply()
        Log.d(TAG, "Saved last instance ID for ScreenMask: ${instanceIdCounter.get()}")
    }

    private fun updateActiveInstanceCountInPrefs() {
        servicePrefs.edit().putInt(KEY_ACTIVE_COUNT, activeMaskViewModels.size).apply()
        Log.d(TAG, "Updated active ScreenMask count: ${activeMaskViewModels.size}")
    }

    private fun loadAndRestoreMaskStates() {
        lifecycleScope.launch(Dispatchers.IO) {
            val persistedStates = screenMaskDao.getAllStates()
            if (persistedStates.isNotEmpty()) {
                Log.d(TAG, "Found ${persistedStates.size} persisted screen mask states. Restoring...")
                var maxId = instanceIdCounter.get()
                persistedStates.forEach { entity ->
                    maxId = max(maxId, entity.instanceId)
                    // Initialize ViewModel for this persisted ID.
                    // The ViewModel's init block will load the specific state from DB.
                    // The view will be created/updated when the ViewModel emits its state.
                    launch(Dispatchers.Main) { // Ensure VM init is on main if it touches LiveData immediately
                        initializeViewModel(entity.instanceId, Bundle().apply { putInt(ScreenMaskViewModel.KEY_INSTANCE_ID, entity.instanceId) })
                    }
                }
                instanceIdCounter.set(maxId) // Ensure counter is beyond highest loaded ID
            }

            // Ensure service is in foreground if any masks were restored or become active via VM loading
            if (activeMaskViewModels.isNotEmpty()) { // Check after potential restorations
                startForegroundServiceIfNeeded()
            }
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.d(TAG, "onStartCommand: Action: $action")

        when (action) {
            ACTION_START_SCREEN_MASK -> {
                startForegroundServiceIfNeeded()
                if (activeMaskViewModels.isEmpty() && servicePrefs.getInt(KEY_ACTIVE_COUNT, 0) == 0) {
                    Log.d(TAG, "No active masks (from map and prefs), adding a new default one.")
                    handleAddNewMaskInstance()
                } else {
                    Log.d(TAG, "Screen Mask started, existing instances will be managed by their ViewModels.")
                }
            }
            ACTION_ADD_NEW_MASK_INSTANCE -> {
                startForegroundServiceIfNeeded()
                handleAddNewMaskInstance()
            }
            ACTION_STOP_SCREEN_MASK_SERVICE -> {
                stopAllInstancesAndService()
            }
            ACTION_BILLBOARD_IMAGE_SELECTED -> {
                val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
                val targetId = imageChooserTargetInstanceId ?: intent.getIntExtra(EXTRA_MASK_INSTANCE_ID, -1)

                if (targetId != -1) {
                    activeMaskViewModels[targetId]?.setBillboardImageUri(uriString) // ViewModel handles null URI
                } else {
                    Log.w(TAG, "Billboard image selected but no targetInstanceId found.")
                }
                imageChooserTargetInstanceId = null // Clear target
            }
        }
        return START_STICKY
    }

    private fun handleAddNewMaskInstance() {
        if (activeMaskViewModels.size >= MAX_MASKS) {
            Log.w(TAG, "Maximum number of masks ($MAX_MASKS) reached.")
            // The settings UI should display the Snackbar. Service just doesn't add.
            return
        }

        val newInstanceId = instanceIdCounter.incrementAndGet()
        Log.d(TAG, "Adding new mask instance with ID: $newInstanceId")

        // Initialize VM (it will create default state and save it via its init block)
        val initialArgs = Bundle().apply { putInt(ScreenMaskViewModel.KEY_INSTANCE_ID, newInstanceId) }
        initializeViewModel(newInstanceId, initialArgs)
        // View creation/update is handled by the ViewModel's state observer

        saveLastInstanceId()
        updateActiveInstanceCountInPrefs()
        startForegroundServiceIfNeeded() // Ensure foreground if adding the first mask
    }

    private fun initializeViewModel(id: Int, initialArgs: Bundle?): ScreenMaskViewModel {
        return activeMaskViewModels.computeIfAbsent(id) {
            Log.d(TAG, "Creating ScreenMaskViewModel for ID: $id")
            // Directly use the ViewModelProvider with the intended HiltViewModelFactory.
            ViewModelProvider(this, HiltViewModelFactory(this, initialArgs ?: Bundle().apply { putInt(ScreenMaskViewModel.KEY_INSTANCE_ID, id) }, viewModelFactory))
                .get(ScreenMaskViewModel::class.java)
                .also { vm ->
                    observeViewModelState(id, vm)
                }
        }
    }

    private fun observeViewModelState(instanceId: Int, viewModel: ScreenMaskViewModel) {
        stateObserverJobs[instanceId]?.cancel()
        stateObserverJobs[instanceId] = lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                Log.d(TAG, "State update for Mask ID $instanceId: Pos=(${state.x},${state.y}), Size=(${state.width}x${state.height}), Locked=${state.isLocked}, Controls=${state.isControlsVisible}")
                addOrUpdateMaskView(instanceId, state)
            }
        }
        Log.d(TAG, "Started observing ViewModel for Mask ID $instanceId")
    }

    private fun addOrUpdateMaskView(instanceId: Int, state: ScreenMaskState) {
        Handler(Looper.getMainLooper()).post { // Ensure UI operations on Main thread
            var maskView = activeMaskViews[instanceId]
            var params = maskLayoutParams[instanceId]

            if (maskView == null) {
                Log.d(TAG, "Creating new MaskView UI for ID: $instanceId")
                params = createDefaultLayoutParams(state) // Create params based on initial state
                maskView = MaskView(this, instanceId = instanceId).apply {
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    interactionListener = createMaskInteractionListener(instanceId, this, params)
                }
                activeMaskViews[instanceId] = maskView
                maskLayoutParams[instanceId] = params

                try {
                    windowManager.addView(maskView, params)
                    Log.d(TAG, "Added MaskView ID $instanceId to WindowManager.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding MaskView ID $instanceId to WindowManager", e)
                    activeMaskViews.remove(instanceId)
                    maskLayoutParams.remove(instanceId)
                    stateObserverJobs[instanceId]?.cancel()

                    val viewModel = activeMaskViewModels.remove(instanceId)
                    viewModel?.deleteState()
                    updateActiveInstanceCountInPrefs()
                    return@post
                }
            }

            maskView.updateState(state)

            // Update WindowManager.LayoutParams if position/size changed in state
            var layoutNeedsUpdate = false
            if (params!!.x != state.x || params.y != state.y) {
                params.x = state.x
                params.y = state.y
                layoutNeedsUpdate = true
            }
            val newWidth = if (state.width <= 0) WindowManager.LayoutParams.MATCH_PARENT else state.width
            val newHeight = if (state.height <= 0) WindowManager.LayoutParams.MATCH_PARENT else state.height
            if (params.width != newWidth || params.height != newHeight) {
                params.width = newWidth
                params.height = newHeight
                layoutNeedsUpdate = true
            }

            if (layoutNeedsUpdate && maskView.isAttachedToWindow) {
                try {
                    windowManager.updateViewLayout(maskView, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating WindowManager layout for Mask ID $instanceId", e)
                }
            }
        }
    }

    private fun removeMaskInstance(instanceId: Int) {
        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "Removing Mask instance ID: $instanceId")
            val maskView = activeMaskViews.remove(instanceId)
            maskLayoutParams.remove(instanceId)
            stateObserverJobs[instanceId]?.cancel()
            stateObserverJobs.remove(instanceId)
            val viewModel = activeMaskViewModels.remove(instanceId)

            maskView?.let {
                if (it.isAttachedToWindow) {
                    try { windowManager.removeView(it) }
                    catch (e: Exception) { Log.e(TAG, "Error removing MaskView ID $instanceId", e) }
                }
            }
            viewModel?.deleteState() // Tell ViewModel to delete its persisted state
            updateActiveInstanceCountInPrefs()

            if (activeMaskViewModels.isEmpty()) {
                Log.d(TAG, "No active masks left, stopping service.")
                stopService()
            }
        }
    }

    private fun createMaskInteractionListener(
        instanceId: Int,
        maskView: MaskView,
        params: WindowManager.LayoutParams
    ): MaskView.InteractionListener {
        return object : MaskView.InteractionListener {
            override fun onMaskMoved(id: Int, x: Int, y: Int) {
                activeMaskViewModels[id]?.updatePosition(x, y)
            }
            override fun onMaskResized(id: Int, width: Int, height: Int) {
                activeMaskViewModels[id]?.updateSize(width, height)
            }
            override fun onLockToggled(id: Int) {
                activeMaskViewModels[id]?.toggleLock()
            }
            override fun onCloseRequested(id: Int) {
                removeMaskInstance(id)
            }
            override fun onBillboardTapped(id: Int) {
                imageChooserTargetInstanceId = id
                val activityIntent = Intent(this@ScreenMaskService, ScreenMaskActivity::class.java).apply {
                    action = ScreenMaskActivity.ACTION_LAUNCH_IMAGE_CHOOSER_FROM_SERVICE
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(activityIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start ScreenMaskActivity for image chooser", e)
                    Toast.makeText(this@ScreenMaskService, "Could not open image picker.", Toast.LENGTH_SHORT).show()
                    imageChooserTargetInstanceId = null
                }
            }
            override fun onColorChangeRequested(id: Int) {
                // Color changing is removed for now, as masks are opaque black.
                // If this is re-added, call ViewModel: activeMaskViewModels[id]?.updateColor(newColor)
                Log.d(TAG, "Color change requested for $id (currently no-op for opaque masks)")
            }
            override fun onControlsToggled(id: Int) {
                activeMaskViewModels[id]?.toggleControlsVisibility()
            }
        }
    }

    private fun createDefaultLayoutParams(initialState: ScreenMaskState): WindowManager.LayoutParams {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Default to full screen if width/height are invalid or 0
        val width = if (initialState.width <= 0) screenWidth else initialState.width
        val height = if (initialState.height <= 0) screenHeight else initialState.height

        // Ensure x and y are within bounds if dimensions are smaller than screen
        // If it's full screen, x/y should be 0.
        val x = if (width >= screenWidth) 0 else initialState.x.coerceIn(0, screenWidth - width)
        val y = if (height >= screenHeight) 0 else initialState.y.coerceIn(0, screenHeight - height)


        return WindowManager.LayoutParams(
            width, height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT // Important for overlays
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun stopAllInstancesAndService() {
        Log.d(TAG, "Stopping all instances and Screen Mask service.")
        activeMaskViewModels.keys.toList().forEach { id -> removeMaskInstance(id) }
        // removeMaskInstance calls stopService if map becomes empty. Ensure it's called if map is already empty.
        if (activeMaskViewModels.isEmpty()) {
            stopService()
        }
    }

    private fun stopService() {
        Log.d(TAG, "stopService called for Screen Mask")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isForeground = false
    }

    private fun startForegroundServiceIfNeeded() {
        if (isForeground) return
        val notification = createNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
            Log.d(TAG, "ScreenMaskService started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service for ScreenMask", e)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, ScreenMaskActivity::class.java).apply {
            action = ACTION_START_SCREEN_MASK // Action to re-evaluate if service should show UI
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Mask Active") // More specific title
            .setContentText("Tap to manage screen masks.") // More specific text
            .setSmallIcon(R.drawable.ic_mask)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Mask Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stateObserverJobs.values.forEach { it.cancel() }
        stateObserverJobs.clear()
        activeMaskViews.keys.toList().forEach { id ->
            val view = activeMaskViews.remove(id)
            maskLayoutParams.remove(id)
            view?.let {
                if (it.isAttachedToWindow) {
                    try { windowManager.removeView(it) } catch (e:Exception) { Log.e(TAG, "Error removing view on destroy for $id")}
                }
            }
        }
        activeMaskViewModels.clear()
        saveLastInstanceId()
        _viewModelStore.clear() // Clear the ViewModelStore
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}

// Add a Hilt qualifier for ScreenMask specific SharedPreferences if not already globally defined
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ScreenMaskPrefs

// Add to your DI module (e.g., AppModule.kt or a new ServiceModule.kt)
/*
@Module
@InstallIn(ServiceComponent::class) // Or SingletonComponent if prefs are app-wide
object ScreenMaskServiceModule {
    @Provides
    @ScreenMaskPrefs
    fun provideScreenMaskPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences(ScreenMaskService.PREFS_NAME_FOR_ACTIVITY, Context.MODE_PRIVATE)
    }
}
*/