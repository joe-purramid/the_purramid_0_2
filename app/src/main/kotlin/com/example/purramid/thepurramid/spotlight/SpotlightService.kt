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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import com.example.purramid.thepurramid.MainActivity
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.SpotlightDao
import com.example.purramid.thepurramid.di.HiltViewModelFactory
import com.example.purramid.thepurramid.di.SpotlightPrefs
import com.example.purramid.thepurramid.spotlight.SpotlightUiState
import com.example.purramid.thepurramid.spotlight.viewmodel.SpotlightViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

// Define Actions
const val ACTION_START_SPOTLIGHT_SERVICE = "com.example.purramid.spotlight.ACTION_START_SERVICE"
const val ACTION_STOP_SPOTLIGHT_SERVICE = "com.example.purramid.spotlight.ACTION_STOP_SERVICE"
const val ACTION_ADD_NEW_SPOTLIGHT_INSTANCE = "com.example.purramid.spotlight.ACTION_ADD_NEW_INSTANCE"

@AndroidEntryPoint
class SpotlightService : LifecycleService(), ViewModelStoreOwner {

    @Inject lateinit var windowManager: WindowManager
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory // Hilt provides default
    @Inject lateinit var spotlightDao: SpotlightDao // For restoring state if needed
    @Inject @SpotlightPrefs lateinit var servicePrefs: SharedPreferences

    private val _viewModelStore = ViewModelStore()
    override fun getViewModelStore(): ViewModelStore = _viewModelStore

    private val activeSpotlightViewModels = ConcurrentHashMap<Int, SpotlightViewModel>()
    private val activeSpotlightViews = ConcurrentHashMap<Int, SpotlightView>()
    private val spotlightLayoutParams = ConcurrentHashMap<Int, WindowManager.LayoutParams>()
    private val stateObserverJobs = ConcurrentHashMap<Int, Job>()

    private val instanceIdCounter = AtomicInteger(0)
    private var isForeground = false

    companion object {
        private const val TAG = "SpotlightService"
        private const val NOTIFICATION_ID = 3 // Was 3, ensure uniqueness
        private const val CHANNEL_ID = "SpotlightServiceChannel"
        const val MAX_SPOTLIGHTS = 4
        const val KEY_INSTANCE_ID = "spotlight_instance_id"
        const val PREFS_NAME_FOR_ACTIVITY = "spotlight_service_prefs"
        const val KEY_ACTIVE_COUNT_FOR_ACTIVITY = "active_spotlight_count"
        const val KEY_LAST_INSTANCE_ID = "last_spotlight_instance_id"
        private const val PASS_THROUGH_DELAY_MS = 50L
    }

    private val handler = Handler(Looper.getMainLooper())


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        loadLastInstanceId()
        loadAndRestoreSpotlightStates()
    }

    private fun loadLastInstanceId() {
        val lastId = servicePrefs.getInt(KEY_LAST_INSTANCE_ID, 0)
        instanceIdCounter.set(lastId)
        Log.d(TAG, "Loaded last instance ID for Spotlight: $lastId")
    }

    private fun saveLastInstanceId() {
        servicePrefs.edit().putInt(KEY_LAST_INSTANCE_ID, instanceIdCounter.get()).apply()
        Log.d(TAG, "Saved last instance ID for Spotlight: ${instanceIdCounter.get()}")
    }

    private fun updateActiveInstanceCountInPrefs() {
        servicePrefs.edit().putInt(KEY_ACTIVE_COUNT_FOR_ACTIVITY, activeSpotlightViewModels.size).apply()
        Log.d(TAG, "Updated active Spotlight count: ${activeSpotlightViewModels.size}")
    }

    private fun loadAndRestoreSpotlightStates() {
        lifecycleScope.launch(Dispatchers.IO) {
            val persistedStates = spotlightDao.getAllSpotlights() // Assuming getAllSpotlights exists
            if (persistedStates.isNotEmpty()) {
                Log.d(TAG, "Found ${persistedStates.size} persisted spotlight states. Restoring...")
                var maxId = instanceIdCounter.get()
                persistedStates.forEach { entity ->
                    maxId = maxOf(maxId, entity.id)
                    launch(Dispatchers.Main) {
                        initializeViewModel(entity.id, Bundle().apply { putInt(SpotlightViewModel.KEY_INSTANCE_ID, entity.id) })
                    }
                }
                instanceIdCounter.set(maxId)
            }
            if (activeSpotlightViewModels.isNotEmpty()) {
                startForegroundServiceIfNeeded()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.d(TAG, "onStartCommand: Action: $action")

        when (action) {
            ACTION_START_SPOTLIGHT_SERVICE -> {
                startForegroundServiceIfNeeded()
                if (activeSpotlightViewModels.isEmpty() && servicePrefs.getInt(KEY_ACTIVE_COUNT_FOR_ACTIVITY, 0) == 0) {
                    Log.d(TAG, "No active spotlights, adding a new default one.")
                    handleAddNewSpotlightInstance()
                }
            }
            ACTION_ADD_NEW_SPOTLIGHT_INSTANCE -> {
                startForegroundServiceIfNeeded()
                handleAddNewSpotlightInstance()
            }
            ACTION_STOP_SPOTLIGHT_SERVICE -> {
                stopAllInstancesAndService()
            }
        }
        return START_STICKY
    }

    private fun handleAddNewSpotlightInstance() {
        if (activeSpotlightViewModels.size >= MAX_SPOTLIGHTS) {
            Log.w(TAG, "Maximum number of spotlights ($MAX_SPOTLIGHTS) reached.")
            // Inform UI via settings screen (Snackbar)
            return
        }
        val newInstanceId = instanceIdCounter.incrementAndGet()
        Log.d(TAG, "Adding new spotlight instance with ID: $newInstanceId")

        val initialArgs = Bundle().apply { putInt(SpotlightViewModel.KEY_INSTANCE_ID, newInstanceId) }
        initializeViewModel(newInstanceId, initialArgs)
        // View creation handled by observer

        saveLastInstanceId()
        updateActiveInstanceCountInPrefs()
        startForegroundServiceIfNeeded()
    }

    private fun initializeViewModel(id: Int, initialArgs: Bundle?): SpotlightViewModel {
        return activeSpotlightViewModels.computeIfAbsent(id) {
            Log.d(TAG, "Creating SpotlightViewModel for ID: $id")
            ViewModelProvider(this, HiltViewModelFactory(this, initialArgs ?: Bundle().apply { putInt(SpotlightViewModel.KEY_INSTANCE_ID, id) }, viewModelFactory))
                .get(SpotlightViewModel::class.java)
                .also { vm ->
                    observeViewModelState(id, vm)
                }
        }
    }

    private fun observeViewModelState(instanceId: Int, viewModel: SpotlightViewModel) {
        stateObserverJobs[instanceId]?.cancel()
        stateObserverJobs[instanceId] = lifecycleScope.launch {
            viewModel.uiState.collectLatest { state: SpotlightUiState ->
                Log.d(TAG, "State update for Spotlight ID $instanceId: ${state.spotlights.size} items, Shape: ${state.globalShape}")
                // A Spotlight overlay manages ALL spotlights data from its corresponding ViewModel.
                // So, we pass the whole list from the state.
                addOrUpdateSpotlightOverlayView(instanceId, state)
            }
        }
        Log.d(TAG, "Started observing ViewModel for Spotlight ID $instanceId")
    }

    private fun addOrUpdateSpotlightOverlayView(instanceId: Int, state: SpotlightUiState) {
        Handler(Looper.getMainLooper()).post {
            var spotlightViewInstance = activeSpotlightViews[instanceId]
            var params = spotlightLayoutParams[instanceId]

            if (spotlightViewInstance == null) {
                Log.d(TAG, "Creating new SpotlightView UI for ID: $instanceId")
                params = createDefaultLayoutParams(state.spotlights.firstOrNull()) // Pass first spotlight for initial sizing
                spotlightViewInstance = SpotlightView(this, null).apply {
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    this.interactionListener = createSpotlightInteractionListener(instanceId, this, params)
                    tag = instanceId // Set tag for identification if needed
                }
                activeSpotlightViews[instanceId] = spotlightViewInstance
                spotlightLayoutParams[instanceId] = params

                try {
                    if (!spotlightViewInstance.isAttachedToWindow) {
                        windowManager.addView(spotlightViewInstance, params)
                        Log.d(TAG, "Added SpotlightView ID $instanceId to WindowManager.")
                    } else {
                        Log.w(TAG, "Attempted to add SpotlightView ID $instanceId but it was already attached.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding SpotlightView ID $instanceId to WindowManager", e)
                    // Cleanup if addView fails
                    activeSpotlightViews.remove(instanceId)
                    spotlightLayoutParams.remove(instanceId)
                    stateObserverJobs[instanceId]?.cancel()
                    activeSpotlightViewModels.remove(instanceId)
                    updateActiveInstanceCountInPrefs()
                    return@post
                }
            }

            // The SpotlightView's updateSpotlights takes the list of data objects and the global shape
            spotlightViewInstance.updateSpotlights(state.spotlights, state.globalShape)

            // Update WindowManager.LayoutParams only for window moves (position)
            // Size is MATCH_PARENT. Individual spotlight data (center, radius) is handled by the view itself.
            val currentSpotlightData = state.spotlights.find { it.id == instanceId } // This model is if one view = one spotlight
            // If one view shows ALL spotlights from its VM, then position is global for the window
            // Let's assume one SpotlightView window per `instanceId` for now, showing one "set" of spotlights from its VM.
            // Window position for Spotlight is typically 0,0 and MATCH_PARENT.
            // If you allow moving the entire Spotlight overlay window:
            // if (params!!.x != state.windowX || params.y != state.windowY) {
            //    params.x = state.windowX
            //    params.y = state.windowY
            //    if (spotlightViewInstance.isAttachedToWindow) {
            //        try { windowManager.updateViewLayout(spotlightViewInstance, params) }
            //        catch (e: Exception) { Log.e(TAG, "Error updating WM layout for $instanceId", e) }
            //    }
            // }
        }
    }

    private fun removeSpotlightInstance(instanceId: Int) {
        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "Removing Spotlight instance ID: $instanceId")
            val spotlightView = activeSpotlightViews.remove(instanceId)
            spotlightLayoutParams.remove(instanceId)
            stateObserverJobs[instanceId]?.cancel()
            stateObserverJobs.remove(instanceId)
            val viewModel = activeSpotlightViewModels.remove(instanceId)

            spotlightView?.let {
                if (it.isAttachedToWindow) {
                    try { windowManager.removeView(it) }
                    catch (e: Exception) { Log.e(TAG, "Error removing SpotlightView ID $instanceId", e) }
                }
            }
            viewModel?.deleteState() // Tell VM to delete from DB
            updateActiveInstanceCountInPrefs()

            if (activeSpotlightViewModels.isEmpty()) {
                Log.d(TAG, "No active spotlights left, stopping service.")
                stopService()
            }
        }
    }

    private fun createSpotlightInteractionListener(
        instanceId: Int,
        view: SpotlightView,
        params: WindowManager.LayoutParams
    ): SpotlightView.SpotlightInteractionListener {
        return object : SpotlightView.SpotlightInteractionListener {
            override fun requestWindowMove(deltaX: Float, deltaY: Float) {
                params.x += deltaX.toInt()
                params.y += deltaY.toInt()
                try { if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params) }
                catch (e: Exception) { Log.e(TAG, "Error updating layout on move for Spotlight ID $instanceId", e)}
            }
            override fun requestWindowMoveFinished() {
                // Persist window position if Spotlight windows are individually movable
                // activeSpotlightViewModels[instanceId]?.updateWindowPosition(params.x, params.y)
            }
            override fun requestUpdateSpotlightState(updatedSpotlight: SpotlightView.Spotlight) {
                activeSpotlightViewModels[instanceId]?.updateSpotlightState(updatedSpotlight)
            }
            override fun requestTapPassThrough() {
                enableTapPassThrough(view, params)
            }
            override fun requestClose(spotlightIdToClose: Int) { // The view passes the ID of the spotlight data object
                // For now, assume closing any spotlight within this instance closes the whole instance view
                Log.d(TAG, "Close requested for Spotlight data ID $spotlightIdToClose within instance $instanceId")
                activeSpotlightViewModels[instanceId]?.deleteSpotlight(spotlightIdToClose)
                // If the VM's list of spotlights becomes empty, it could signal the service to remove this instance.
                // Or, if each 'instanceId' maps to one SpotlightView which shows one spotlight, then:
                // removeSpotlightInstance(instanceId)
            }
            override fun requestShapeChange() {
                activeSpotlightViewModels[instanceId]?.cycleGlobalShape()
            }
            override fun requestAddNew() {
                // This adds a new *spotlight data object* to the current SpotlightView's ViewModel
                val displayMetrics = resources.displayMetrics
                activeSpotlightViewModels[instanceId]?.addSpotlight(displayMetrics.widthPixels, displayMetrics.heightPixels)
            }
        }
    }

    // Params for the main SpotlightView window (usually fullscreen)
    private fun createDefaultLayoutParams(firstSpotlightData: SpotlightView.Spotlight?): WindowManager.LayoutParams {
        // Spotlight overlay usually covers the entire screen to create the "mask" effect
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    private fun enableTapPassThrough(view: SpotlightView, params: WindowManager.LayoutParams) {
        if ((params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params) }
            catch (e: Exception) { Log.e(TAG, "Error enabling pass-through for $view", e) }
            handler.postDelayed({ removeTapPassThroughFlag(view, params) }, PASS_THROUGH_DELAY_MS)
        }
    }

    private fun removeTapPassThroughFlag(view: SpotlightView, params: WindowManager.LayoutParams) {
        if ((params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params) }
            catch (e: Exception) { Log.e(TAG, "Error disabling pass-through for $view", e) }
        }
    }

    private fun stopAllInstancesAndService() {
        Log.d(TAG, "Stopping all instances and Spotlight service.")
        activeSpotlightViewModels.keys.toList().forEach { id -> removeSpotlightInstance(id) }
        if (activeSpotlightViewModels.isEmpty()) { stopService() }
    }

    private fun stopService() {
        Log.d(TAG, "stopService called for Spotlight")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isForeground = false
    }

    private fun startForegroundServiceIfNeeded() {
        if (isForeground) return
        // ... (createNotification code as before) ...
        val notification = createNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
            Log.d(TAG, "SpotlightService started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service for Spotlight", e)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Spotlight Active")
            .setContentText("Spotlight overlay is active.")
            .setSmallIcon(R.drawable.ic_spotlight)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Spotlight Service Channel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stateObserverJobs.values.forEach { it.cancel() }
        stateObserverJobs.clear()
        activeSpotlightViews.keys.toList().forEach { id ->
            val view = activeSpotlightViews.remove(id)
            spotlightLayoutParams.remove(id)
            view?.let { if (it.isAttachedToWindow) try { windowManager.removeView(it) } catch (e:Exception){ Log.e(TAG, "Error removing view $id on destroy")} }
        }
        activeSpotlightViewModels.clear()
        saveLastInstanceId()
        _viewModelStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}