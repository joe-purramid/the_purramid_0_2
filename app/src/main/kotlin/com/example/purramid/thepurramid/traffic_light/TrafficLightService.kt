// TrafficLightService.kt
package com.example.purramid.thepurramid.traffic_light

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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import com.example.purramid.thepurramid.MainActivity
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.TrafficLightDao
import com.example.purramid.thepurramid.di.HiltViewModelFactory
import com.example.purramid.thepurramid.di.TrafficLightPrefs
import com.example.purramid.thepurramid.instance.InstanceManager
import com.example.purramid.thepurramid.traffic_light.viewmodel.LightColor
import com.example.purramid.thepurramid.traffic_light.viewmodel.TrafficLightState
import com.example.purramid.thepurramid.traffic_light.viewmodel.TrafficLightViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

// Actions
const val ACTION_START_TRAFFIC_LIGHT_SERVICE = "com.example.purramid.traffic_light.ACTION_START_SERVICE"
const val ACTION_STOP_TRAFFIC_LIGHT_SERVICE = "com.example.purramid.traffic_light.ACTION_STOP_SERVICE"
const val ACTION_ADD_NEW_TRAFFIC_LIGHT_INSTANCE = "com.example.purramid.traffic_light.ACTION_ADD_NEW_INSTANCE"
const val EXTRA_TRAFFIC_LIGHT_INSTANCE_ID = TrafficLightViewModel.KEY_INSTANCE_ID
const val PREFS_NAME = "com.example.purramid.thepurramid.traffic_light.APP_PREFERENCES"

@AndroidEntryPoint
class TrafficLightService : LifecycleService(), ViewModelStoreOwner {

    @Inject lateinit var windowManager: WindowManager
    @Inject lateinit var instanceManager: InstanceManager
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var trafficLightDao: TrafficLightDao
    @Inject @TrafficLightPrefs lateinit var servicePrefs: SharedPreferences

    private val _viewModelStore = ViewModelStore()
    override fun getViewModelStore(): ViewModelStore = _viewModelStore

    private val activeTrafficLightViewModels = ConcurrentHashMap<Int, TrafficLightViewModel>()
    private val activeTrafficLightViews = ConcurrentHashMap<Int, TrafficLightOverlayView>()
    private val trafficLightLayoutParams = ConcurrentHashMap<Int, WindowManager.LayoutParams>()
    private val stateObserverJobs = ConcurrentHashMap<Int, Job>()

    private var isForeground = false

    companion object {
        private const val TAG = "TrafficLightService"
        private const val NOTIFICATION_ID = 4
        private const val CHANNEL_ID = "TrafficLightServiceChannel"
        const val MAX_TRAFFIC_LIGHTS = 4
        const val PREFS_NAME_FOR_ACTIVITY = "traffic_light_service_state_prefs" // Define if not in Activity
        const val KEY_ACTIVE_COUNT_FOR_ACTIVITY = "active_trafficlight_count"
        const val KEY_LAST_INSTANCE_ID = "last_instance_id_trafficlight"
    }
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        loadAndRestoreStates()
    }

    private fun updateActiveInstanceCountInPrefs() {
        servicePrefs.edit().putInt(KEY_ACTIVE_COUNT_FOR_ACTIVITY, activeTrafficLightViewModels.size).apply()
        Log.d(TAG, "Updated active TrafficLight count: ${activeTrafficLightViewModels.size}")
    }

    private fun loadAndRestoreStates() {
        lifecycleScope.launch(Dispatchers.IO) {
            val persistedStates = trafficLightDao.getAllStates()
            if (persistedStates.isNotEmpty()) {
                Log.d(TAG, "Found ${persistedStates.size} persisted traffic light states. Restoring...")
                persistedStates.forEach { entity ->
                    // Register the existing instance ID
                    instanceManager.registerExistingInstance(InstanceManager.TRAFFIC_LIGHT, entity.instanceId)

                    launch(Dispatchers.Main) {
                        initializeViewModel(entity.instanceId, Bundle().apply {
                            putInt(TrafficLightViewModel.KEY_INSTANCE_ID, entity.instanceId)
                        })
                    }
                }
            }

            if (activeTrafficLightViewModels.isNotEmpty()) {
                startForegroundServiceIfNeeded()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.d(TAG, "onStartCommand: Action: $action")

        when (action) {
            ACTION_START_TRAFFIC_LIGHT_SERVICE -> {
                startForegroundServiceIfNeeded()
                if (activeTrafficLightViewModels.isEmpty() && servicePrefs.getInt(KEY_ACTIVE_COUNT_FOR_ACTIVITY, 0) == 0) {
                    Log.d(TAG, "No active traffic lights, adding a new default one.")
                    handleAddNewTrafficLightInstance()
                }
            }
            ACTION_ADD_NEW_TRAFFIC_LIGHT_INSTANCE -> {
                startForegroundServiceIfNeeded()
                handleAddNewTrafficLightInstance()
            }
            ACTION_STOP_TRAFFIC_LIGHT_SERVICE -> {
                stopAllInstancesAndService()
            }
        }
        return START_STICKY
    }

    private fun handleAddNewTrafficLightInstance() {
        val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.TRAFFIC_LIGHT)
        if (activeCount >= MAX_TRAFFIC_LIGHTS) {
            Log.w(TAG, "Maximum number of traffic lights ($MAX_TRAFFIC_LIGHTS) reached.")
            return
        }

        val newInstanceId = instanceManager.getNextInstanceId(InstanceManager.TRAFFIC_LIGHT)
        if (newInstanceId == null) {
            Log.w(TAG, "No available instance slots for Traffic Light")
            return
        }

        Log.d(TAG, "Adding new traffic light instance with ID: $newInstanceId")

        // Initialize VM (it will create default state and save it via its init block)
        val initialArgs = Bundle().apply { putInt(TrafficLightViewModel.KEY_INSTANCE_ID, newInstanceId) }
        initializeViewModel(newInstanceId, initialArgs)

        updateActiveInstanceCountInPrefs()
        startForegroundServiceIfNeeded()
    }

    private fun initializeViewModel(id: Int, initialArgs: Bundle?): TrafficLightViewModel {
        return activeTrafficLightViewModels.computeIfAbsent(id) {
            Log.d(TAG, "Creating TrafficLightViewModel for ID: $id")
            ViewModelProvider(this, HiltViewModelFactory(this, initialArgs ?: Bundle().apply { putInt(TrafficLightViewModel.KEY_INSTANCE_ID, id) }, viewModelFactory))
                .get(TrafficLightViewModel::class.java)
                .also { vm ->
                    observeViewModelState(id, vm)
                }
        }
    }

    private fun observeViewModelState(instanceId: Int, viewModel: TrafficLightViewModel) {
        stateObserverJobs[instanceId]?.cancel()
        stateObserverJobs[instanceId] = lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                Log.d(TAG, "State update for TrafficLight ID $instanceId: Mode=${state.currentMode}, Active=${state.activeLight}")
                addOrUpdateTrafficLightOverlayView(instanceId, state)
            }
        }
        Log.d(TAG, "Started observing ViewModel for TrafficLight ID $instanceId")
    }

    private fun addOrUpdateTrafficLightOverlayView(instanceId: Int, state: TrafficLightState) {
        handler.post {
            var view = activeTrafficLightViews[instanceId]
            var params = trafficLightLayoutParams[instanceId]

            if (view == null) {
                Log.d(TAG, "Creating new TrafficLightOverlayView UI for ID: $instanceId")
                params = createDefaultLayoutParams(state)
                view = TrafficLightOverlayView(this, instanceId = instanceId).apply { // Pass instanceId
                    interactionListener = createTrafficLightInteractionListener(instanceId, this, params)
                }
                activeTrafficLightViews[instanceId] = view
                trafficLightLayoutParams[instanceId] = params

                try {
                    windowManager.addView(view, params)
                    Log.d(TAG, "Added TrafficLightOverlayView ID $instanceId to WindowManager.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding TrafficLightOverlayView ID $instanceId", e)
                    activeTrafficLightViews.remove(instanceId)
                    trafficLightLayoutParams.remove(instanceId)
                    stateObserverJobs[instanceId]?.cancel()
                    activeTrafficLightViewModels.remove(instanceId)?.onCleared()
                    updateActiveInstanceCountInPrefs()
                    return@post
                }
            }

            view.updateState(state)

            var layoutNeedsUpdate = false
            if (params!!.x != state.windowX || params.y != state.windowY) {
                params.x = state.windowX
                params.y = state.windowY
                layoutNeedsUpdate = true
            }
            val newWidth = if (state.windowWidth > 0) state.windowWidth else params.width
            val newHeight = if (state.windowHeight > 0) state.windowHeight else params.height
            if (params.width != newWidth || params.height != newHeight) {
                params.width = newWidth
                params.height = newHeight
                layoutNeedsUpdate = true
            }

            if (layoutNeedsUpdate && view.isAttachedToWindow) {
                try { windowManager.updateViewLayout(view, params) }
                catch (e: Exception) { Log.e(TAG, "Error updating WM layout for TL ID $instanceId", e) }
            }
        }
    }

    private fun removeTrafficLightInstance(instanceId: Int) {
        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "Removing Traffic Light instance ID: $instanceId")

            // Release the instance ID back to the manager
            instanceManager.releaseInstanceId(InstanceManager.TRAFFIC_LIGHT, instanceId)

            val trafficLightView = activeTrafficLightViews.remove(instanceId)
            trafficLightLayoutParams.remove(instanceId)
            stateObserverJobs[instanceId]?.cancel()
            stateObserverJobs.remove(instanceId)
            val viewModel = activeTrafficLightViewModels.remove(instanceId)

            trafficLightView?.let {
                if (it.isAttachedToWindow) {
                    try { windowManager.removeView(it) }
                    catch (e: Exception) { Log.e(TAG, "Error removing TrafficLightView ID $instanceId", e) }
                }
            }
            viewModel?.deleteState()
            updateActiveInstanceCountInPrefs()

            if (activeTrafficLightViewModels.isEmpty()) {
                Log.d(TAG, "No active traffic lights left, stopping service.")
                stopService()
            }
        }
    }

    private fun createTrafficLightInteractionListener(
        instanceId: Int,
        view: TrafficLightOverlayView,
        params: WindowManager.LayoutParams
    ): TrafficLightOverlayView.InteractionListener {
        return object : TrafficLightOverlayView.InteractionListener {
            override fun onLightTapped(id: Int, color: LightColor) { // id here is instanceId
                activeTrafficLightViewModels[id]?.handleLightTap(color)
            }
            override fun onCloseRequested(id: Int) {
                removeTrafficLightInstance(id)
            }
            override fun onSettingsRequested(id: Int) {
                val settingsIntent = Intent(this@TrafficLightService, TrafficLightActivity::class.java).apply {
                    action = TrafficLightActivity.ACTION_SHOW_SETTINGS
                    putExtra(EXTRA_TRAFFIC_LIGHT_INSTANCE_ID, id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                try { startActivity(settingsIntent) }
                catch (e: Exception) { Log.e(TAG, "Error starting TrafficLightActivity for settings ID $id", e)}
            }
            override fun onMove(id: Int, rawDeltaX: Float, rawDeltaY: Float) {
                params.x += rawDeltaX.toInt()
                params.y += rawDeltaY.toInt()
                try { if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params) }
                catch (e: Exception) { Log.e(TAG, "Error updating layout on move for TL ID $id", e)}
            }
            override fun onMoveFinished(id: Int) {
                activeTrafficLightViewModels[id]?.saveWindowPosition(params.x, params.y)
            }
            override fun onResize(id: Int, newWidth: Int, newHeight: Int) {
                params.width = newWidth
                params.height = newHeight
                try { if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params) }
                catch (e: Exception) { Log.e(TAG, "Error updating layout on resize for TL ID $id", e)}
            }
            override fun onResizeFinished(id: Int, finalWidth: Int, finalHeight: Int) {
                activeTrafficLightViewModels[id]?.saveWindowSize(finalWidth, finalHeight)
            }
        }
    }

    private fun createDefaultLayoutParams(initialState: TrafficLightState?): WindowManager.LayoutParams {
        val displayMetrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        val defaultWidth = resources.getDimensionPixelSize(R.dimen.traffic_light_default_width)
        val defaultHeight = resources.getDimensionPixelSize(R.dimen.traffic_light_default_height)

        val width = initialState?.let { if (it.windowWidth > 0) it.windowWidth else defaultWidth } ?: defaultWidth
        val height = initialState?.let { if (it.windowHeight > 0) it.windowHeight else defaultHeight } ?: defaultHeight
        val x = initialState?.windowX ?: (displayMetrics.widthPixels / 2 - width / 2)
        val y = initialState?.windowY ?: (displayMetrics.heightPixels / 2 - height / 2)

        return WindowManager.LayoutParams(
            width, height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun stopAllInstancesAndService() {
        Log.d(TAG, "Stopping all instances and TrafficLight service.")
        activeTrafficLightViewModels.keys.toList().forEach { id -> removeTrafficLightInstance(id) }
        if (activeTrafficLightViewModels.isEmpty()) { stopService() }
    }

    private fun stopService() {
        Log.d(TAG, "stopService called for TrafficLight")
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
            Log.d(TAG, "TrafficLightService started in foreground.")
        } catch (e: Exception) { Log.e(TAG, "Error starting foreground service for TrafficLight", e) }
    }

    private fun createNotification(): Notification { /* ... same as before ... */
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Traffic Light Active")
            .setContentText("A traffic light overlay is active.")
            .setSmallIcon(R.drawable.ic_traffic_light) // Ensure this icon exists
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() { /* ... same as before ... */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Traffic Light Service Channel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stateObserverJobs.values.forEach { it.cancel() }
        stateObserverJobs.clear()
        activeTrafficLightViews.keys.toList().forEach { id ->
            val view = activeTrafficLightViews.remove(id)
            trafficLightLayoutParams.remove(id)
            view?.let { if (it.isAttachedToWindow) try { windowManager.removeView(it) } catch (e:Exception){/*ignore*/} }
        }
        activeTrafficLightViewModels.clear()
        _viewModelStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}