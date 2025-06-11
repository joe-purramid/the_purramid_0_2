// ClockOverlayService.kt
package com.example.purramid.thepurramid.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle // For SavedStateHandle args
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast // For max instances message
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import com.caverock.androidsvg.SVGImageView
import com.example.purramid.thepurramid.MainActivity
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.ClockDao // Keep for restoration logic if needed directly
import com.example.purramid.thepurramid.data.db.ClockStateEntity
import com.example.purramid.thepurramid.clock.viewmodel.ClockState
import com.example.purramid.thepurramid.clock.viewmodel.ClockViewModel
import com.example.purramid.thepurramid.di.ClockPrefs // Import Hilt qualifier
import com.example.purramid.thepurramid.di.HiltViewModelFactory // If you have this helper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.math.max

// Actions
const val ACTION_START_CLOCK_SERVICE = "com.example.purramid.clock.ACTION_START_SERVICE" // More generic start
const val ACTION_STOP_CLOCK_SERVICE = "com.example.purramid.clock.ACTION_STOP_SERVICE"

// Existing actions from ClockSettingsActivity, keep if they target specific instances via EXTRA_CLOCK_ID
const val ACTION_ADD_NEW_CLOCK = "com.example.purramid.thepurramid.ACTION_ADD_NEW_CLOCK" // From settings
const val ACTION_UPDATE_CLOCK_SETTING = "com.example.purramid.thepurramid.ACTION_UPDATE_CLOCK_SETTING"
const val ACTION_NEST_CLOCK = "com.example.purramid.thepurramid.ACTION_NEST_CLOCK"
const val EXTRA_CLOCK_ID = ClockViewModel.KEY_CLOCK_ID // Use ViewModel's key
const val EXTRA_SETTING_TYPE = "setting_type"
const val EXTRA_SETTING_VALUE = "setting_value"
const val EXTRA_NEST_STATE = "nest_state"


@AndroidEntryPoint
class ClockOverlayService : LifecycleService(), ViewModelStoreOwner {

    @Inject lateinit var windowManager: WindowManager
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var clockDao: ClockDao // For restoring states
    @Inject @ClockPrefs lateinit var servicePrefs: SharedPreferences


    private val _viewModelStore = ViewModelStore()
    override fun getViewModelStore(): ViewModelStore = _viewModelStore

    private val clockViewModels = ConcurrentHashMap<Int, ClockViewModel>()
    private val activeClockViews = ConcurrentHashMap<Int, ViewGroup>() // Root ViewGroup of the clock layout
    private val clockViewInstances = ConcurrentHashMap<Int, ClockView>() // The actual ClockView custom component
    private val clockLayoutParams = ConcurrentHashMap<Int, WindowManager.LayoutParams>()
    private val stateObserverJobs = ConcurrentHashMap<Int, Job>()

    private val clockIdCounter = AtomicInteger(0)
    private var isForeground = false

    companion object {
        private const val TAG = "ClockOverlayService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "ClockOverlayServiceChannel"
        const val MAX_CLOCKS = 4
        const val PREFS_NAME_FOR_ACTIVITY = "clock_service_state_prefs" // For Activity to read count
        const val KEY_ACTIVE_COUNT_FOR_ACTIVITY = "active_clock_count"
        const val KEY_LAST_INSTANCE_ID = "last_instance_id_clock"
    }
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        loadLastInstanceId()
        loadAndRestoreClockStates()
    }

    private fun loadLastInstanceId() {
        val lastId = servicePrefs.getInt(KEY_LAST_INSTANCE_ID, 0)
        clockIdCounter.set(lastId)
        Log.d(TAG, "Loaded last instance ID for Clock: $lastId")
    }

    private fun saveLastInstanceId() {
        servicePrefs.edit().putInt(KEY_LAST_INSTANCE_ID, clockIdCounter.get()).apply()
        Log.d(TAG, "Saved last instance ID for Clock: ${clockIdCounter.get()}")
    }

    private fun updateActiveInstanceCountInPrefs() {
        servicePrefs.edit().putInt(KEY_ACTIVE_COUNT_FOR_ACTIVITY, clockViewModels.size).apply()
        Log.d(TAG, "Updated active Clock count: ${clockViewModels.size}")
    }

    private fun loadAndRestoreClockStates() {
        lifecycleScope.launch(Dispatchers.IO) {
            val persistedStates = clockDao.getAllStates()
            if (persistedStates.isNotEmpty()) {
                Log.d(TAG, "Found ${persistedStates.size} persisted clock states. Restoring...")
                var maxId = clockIdCounter.get()
                persistedStates.forEach { entity ->
                    maxId = max(maxId, entity.clockId)
                    launch(Dispatchers.Main) {
                        initializeViewModel(entity.clockId, Bundle().apply { putInt(ClockViewModel.KEY_CLOCK_ID, entity.clockId) })
                    }
                }
                clockIdCounter.set(maxId) // Ensure counter is beyond highest loaded ID
            }
            // Ensure service is in foreground if any clocks were restored
            if (clockViewModels.isNotEmpty()) {
                startForegroundServiceIfNeeded()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        val clockId = intent?.getIntExtra(EXTRA_CLOCK_ID, 0) ?: 0 // Default to 0 if not present
        Log.d(TAG, "onStartCommand: Action: $action, ClockID: $clockId")

        when (action) {
            ACTION_START_CLOCK_SERVICE -> {
                startForegroundServiceIfNeeded()
                if (clockViewModels.isEmpty() && servicePrefs.getInt(KEY_ACTIVE_COUNT_FOR_ACTIVITY, 0) == 0) {
                    Log.d(TAG, "No active clocks, adding a new default one.")
                    handleAddNewClockInstance() // This was ACTION_ADD_NEW_CLOCK
                }
            }
            ACTION_ADD_NEW_CLOCK -> { // Renamed from ACTION_ADD_NEW_INSTANCE for clarity
                startForegroundServiceIfNeeded()
                handleAddNewClockInstance()
            }
            ACTION_UPDATE_CLOCK_SETTING -> {
                if (clockId > 0) {
                    handleUpdateClockSetting(intent)
                } else {
                    Log.w(TAG, "ACTION_UPDATE_CLOCK_SETTING missing valid clockId.")
                }
            }
            ACTION_NEST_CLOCK -> {
                if (clockId > 0) {
                    handleNestClock(intent)
                } else {
                    Log.w(TAG, "ACTION_NEST_CLOCK missing valid clockId.")
                }
            }
            ACTION_STOP_CLOCK_SERVICE -> {
                stopAllInstancesAndService()
            }
            else -> {
                Log.w(TAG, "Unhandled or null action received: $action")
            }
        }
        return START_STICKY
    }

    private fun handleAddNewClockInstance() {
        if (clockViewModels.size >= MAX_CLOCKS) {
            Log.w(TAG, "Maximum number of clocks ($MAX_CLOCKS) reached.")
            // Settings UI should show Snackbar
            return
        }
        val newClockId = clockIdCounter.incrementAndGet()
        Log.d(TAG, "Adding new clock instance with ID: $newClockId")

        val initialArgs = Bundle().apply { putInt(ClockViewModel.KEY_CLOCK_ID, newClockId) }
        initializeViewModel(newClockId, initialArgs) // VM loads/creates default state & saves

        saveLastInstanceId()
        updateActiveInstanceCountInPrefs()
        startForegroundServiceIfNeeded()
    }

    private fun initializeViewModel(id: Int, initialArgs: Bundle?): ClockViewModel {
        return clockViewModels.computeIfAbsent(id) {
            Log.d(TAG, "Creating ClockViewModel for ID: $id")
            ViewModelProvider(this, HiltViewModelFactory(this, initialArgs ?: Bundle().apply { putInt(ClockViewModel.KEY_CLOCK_ID, id) }, viewModelFactory))
                .get(ClockViewModel::class.java)
                .also { vm ->
                    observeViewModelState(id, vm)
                }
        }
    }

    private fun observeViewModelState(instanceId: Int, viewModel: ClockViewModel) {
        stateObserverJobs[instanceId]?.cancel()
        stateObserverJobs[instanceId] = lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                Log.d(TAG, "State update for Clock ID $instanceId: Mode=${state.mode}, Time=${state.currentTime}")
                addOrUpdateClockOverlayView(instanceId, state)
            }
        }
        Log.d(TAG, "Started observing ViewModel for Clock ID $instanceId")
    }

    private fun addOrUpdateClockOverlayView(instanceId: Int, state: ClockState) {
        handler.post {
            var rootView = activeClockViews[instanceId]
            var clockView = clockViewInstances[instanceId]
            var params = clockLayoutParams[instanceId]

            val requiredLayoutId = if (state.mode == "analog") R.layout.view_floating_clock_analog else R.layout.view_floating_clock_digital

            if (rootView == null || clockView == null || (rootView.findViewById<View>(R.id.digitalClockView)?.id ?: rootView.findViewById<View>(R.id.analogClockViewContainer)?.id) != (if (state.mode == "analog") R.id.analogClockViewContainer else R.id.digitalClockView)) {
                Log.d(TAG, "Creating/Re-inflating ClockView UI for ID: $instanceId, Mode: ${state.mode}")
                if (rootView != null && rootView.isAttachedToWindow) {
                    try { windowManager.removeView(rootView) } catch (e: Exception) { Log.e(TAG, "Error removing old view for $instanceId", e)}
                }

                params = createDefaultLayoutParams(state)
                val inflater = LayoutInflater.from(this)
                rootView = inflater.inflate(requiredLayoutId, null) as ViewGroup

                if (state.mode == "analog") {
                    val frameLayout = rootView.findViewById<FrameLayout>(R.id.analogClockViewContainer)
                        ?: throw IllegalStateException("Analog layout missing FrameLayout container for ID $instanceId")
                    clockView = ClockView(this, null).apply {
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    }
                    frameLayout.addView(clockView)
                    clockView.setAnalogImageViews(
                        rootView.findViewById(R.id.clockFaceImageView),
                        rootView.findViewById(R.id.hourHandImageView),
                        rootView.findViewById(R.id.minuteHandImageView),
                        rootView.findViewById(R.id.secondHandImageView)
                    )
                } else {
                    clockView = rootView.findViewById(R.id.digitalClockView)
                        ?: throw IllegalStateException("Digital layout missing ClockView for ID $instanceId")
                }

                clockView.setClockId(instanceId)
                clockView.interactionListener = this // Service itself implements listener

                activeClockViews[instanceId] = rootView
                clockViewInstances[instanceId] = clockView
                clockLayoutParams[instanceId] = params

                try {
                    windowManager.addView(rootView, params)
                    Log.d(TAG, "Added/Re-added ClockView ID $instanceId to WindowManager.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding ClockView ID $instanceId to WindowManager", e)
                    activeClockViews.remove(instanceId); clockViewInstances.remove(instanceId); clockLayoutParams.remove(instanceId)
                    stateObserverJobs[instanceId]?.cancel(); activeClockViewModels.remove(instanceId)?.onCleared()
                    updateActiveInstanceCountInPrefs()
                    return@post
                }
            }

            // Update view with state (common properties)
            clockView.setClockMode(state.mode == "analog")
            clockView.setClockColor(state.clockColor)
            clockView.setIs24HourFormat(state.is24Hour)
            clockView.setClockTimeZone(state.timeZoneId)
            clockView.setDisplaySeconds(state.displaySeconds)
            clockView.updateDisplayTime(state.currentTime)

            // Update play/pause button on the root view
            updatePlayPauseButtonOnOverlay(rootView, state.isPaused)
            setupActionButtonsOnOverlay(instanceId, rootView, clockViewModels[instanceId] ?: return@post)


            var layoutNeedsUpdate = false
            if (params!!.x != state.windowX || params.y != state.windowY) {
                params.x = state.windowX
                params.y = state.windowY
                layoutNeedsUpdate = true
            }
            val newWidth = if (state.windowWidth > 0) state.windowWidth else WindowManager.LayoutParams.WRAP_CONTENT
            val newHeight = if (state.windowHeight > 0) state.windowHeight else WindowManager.LayoutParams.WRAP_CONTENT
            if (params.width != newWidth || params.height != newHeight) {
                params.width = newWidth
                params.height = newHeight
                layoutNeedsUpdate = true
            }

            if (layoutNeedsUpdate && rootView.isAttachedToWindow) {
                try { windowManager.updateViewLayout(rootView, params) }
                catch (e: Exception) { Log.e(TAG, "Error updating WM layout for Clock ID $instanceId", e) }
            }
            applyNestModeVisuals(instanceId, rootView, state.isNested)
        }
    }

    private fun removeClockInstance(instanceId: Int) {
        handler.post {
            Log.d(TAG, "Removing Clock instance ID: $instanceId")
            val rootView = activeClockViews.remove(instanceId)
            clockViewInstances.remove(instanceId)
            clockLayoutParams.remove(instanceId)
            stateObserverJobs[instanceId]?.cancel()
            stateObserverJobs.remove(instanceId)
            val viewModel = clockViewModels.remove(instanceId)

            rootView?.let {
                if (it.isAttachedToWindow) {
                    try { windowManager.removeView(it) }
                    catch (e: Exception) { Log.e(TAG, "Error removing ClockView ID $instanceId", e) }
                }
            }
            viewModel?.deleteState()
            updateActiveInstanceCountInPrefs()

            if (clockViewModels.isEmpty()) {
                Log.d(TAG, "No active clocks left, stopping service.")
                stopService()
            }
        }
    }

    // Listener Implementation
    override fun onTimeManuallySet(clockId: Int, newTime: LocalTime) {
        clockViewModels[clockId]?.setManuallySetTime(newTime)
    }

    override fun onDragStateChanged(clockId: Int, isDragging: Boolean) {
        // This might be used to temporarily disable window dragging if hand dragging starts, etc.
        Log.d(TAG, "Clock $clockId drag state changed: $isDragging")
        // The overlay itself also needs a touch listener for window dragging.
        // Ensure that touch events are correctly dispatched or consumed between ClockView and its parent overlay.
    }

    // --- Helper for Overlay Buttons (Play/Pause, Reset, Settings) ---
    private fun setupActionButtonsOnOverlay(clockId: Int, rootView: ViewGroup, viewModel: ClockViewModel) {
        rootView.findViewById<View>(R.id.buttonPlayPause)?.setOnClickListener {
            viewModel.setPaused(!viewModel.uiState.value.isPaused)
        }
        rootView.findViewById<View>(R.id.buttonReset)?.setOnClickListener {
            viewModel.resetTime()
        }
        rootView.findViewById<View>(R.id.buttonSettings)?.setOnClickListener {
            val settingsIntent = Intent(this, ClockSettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ClockViewModel.KEY_CLOCK_ID, clockId)
            }
            startActivity(settingsIntent)
        }
        // Add Touch Listener for Window Drag to the rootView (parent of ClockView)
        setupWindowDragListener(rootView, clockId)
    }

    private fun updatePlayPauseButtonOnOverlay(rootView: ViewGroup, isPaused: Boolean) {
        rootView.findViewById<ImageButton>(R.id.buttonPlayPause)?.apply {
            setImageResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
            contentDescription = getString(if (isPaused) R.string.play else R.string.pause)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWindowDragListener(view: View, clockId: Int) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoving = false
        val touchSlopVal = ViewConfiguration.get(this).scaledTouchSlop

        view.setOnTouchListener { _, event ->
            val params = clockLayoutParams[clockId] ?: return@setOnTouchListener false
            // Allow ClockView to handle its own touch events first (for hand dragging)
            // This assumes ClockView returns true if it handled the event.
            if (clockViewInstances[clockId]?.dispatchTouchEvent(event) == true && event.action != MotionEvent.ACTION_UP) {
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoving = false
                    true // Consume if we might drag
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (!isMoving && (abs(deltaX) > touchSlopVal || abs(deltaY) > touchSlopVal)) {
                        isMoving = true
                    }
                    if (isMoving) {
                        params.x = initialX + deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        try { if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params) }
                        catch (e: Exception) { Log.e(TAG, "Error moving window $clockId", e) }
                    }
                    true // Consume move
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoving) {
                        clockViewModels[clockId]?.updateWindowPosition(params.x, params.y)
                    }
                    isMoving = false
                    // If not moving, and ClockView didn't consume, it's a tap on the overlay background.
                    // Return false to allow potential underlying system actions if desired,
                    // or true if the tap on background should do nothing.
                    // For now, a tap on the overlay background is consumed.
                    true
                }
                else -> false
            }
        }
    }


    // --- Settings Intent Handling ---
    private fun handleUpdateClockSetting(intent: Intent?) {
        val clockId = intent?.getIntExtra(EXTRA_CLOCK_ID, -1) ?: -1
        val settingType = intent?.getStringExtra(EXTRA_SETTING_TYPE)
        val viewModel = clockViewModels[clockId]

        if (viewModel == null || settingType == null) {
            Log.e(TAG, "Cannot update setting, invalid clockId ($clockId) or missing ViewModel/settingType.")
            return
        }
        Log.d(TAG, "Updating setting '$settingType' for clock $clockId")
        when (settingType) {
            "mode" -> viewModel.updateMode(intent.getStringExtra(EXTRA_SETTING_VALUE) ?: "digital")
            "color" -> viewModel.updateColor(intent.getIntExtra(EXTRA_SETTING_VALUE, android.graphics.Color.WHITE))
            "24hour" -> viewModel.updateIs24Hour(intent.getBooleanExtra(EXTRA_SETTING_VALUE, false))
            "time_zone" -> {
                val zoneIdString = intent.getStringExtra(EXTRA_SETTING_VALUE)
                try { zoneIdString?.let { viewModel.updateTimeZone(ZoneId.of(it)) } }
                catch (e: Exception) { Log.e(TAG, "Invalid Zone ID: $zoneIdString", e) }
            }
            "seconds" -> viewModel.updateDisplaySeconds(intent.getBooleanExtra(EXTRA_SETTING_VALUE, true))
            else -> Log.w(TAG, "Unknown setting type: $settingType")
        }
    }

    private fun handleNestClock(intent: Intent?) {
        val clockId = intent?.getIntExtra(EXTRA_CLOCK_ID, -1) ?: -1
        val shouldBeNested = intent?.getBooleanExtra(EXTRA_NEST_STATE, false) ?: false
        val viewModel = clockViewModels[clockId]
        if (viewModel != null) {
            Log.d(TAG, "Setting nest state for clock $clockId to $shouldBeNested")
            viewModel.updateIsNested(shouldBeNested)
            repositionNestedClocks()
        } else {
            Log.e(TAG, "Invalid clockId ($clockId) for ACTION_NEST_CLOCK.")
        }
    }

    // --- Layout and Default Params ---
    private fun createDefaultLayoutParams(state: ClockState?): WindowManager.LayoutParams {
        val displayMetrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        val defaultWidth = WindowManager.LayoutParams.WRAP_CONTENT
        val defaultHeight = WindowManager.LayoutParams.WRAP_CONTENT

        val width = state?.let { if (it.windowWidth > 0) it.windowWidth else defaultWidth } ?: defaultWidth
        val height = state?.let { if (it.windowHeight > 0) it.windowHeight else defaultHeight } ?: defaultHeight
        val x = state?.windowX ?: (displayMetrics.widthPixels / 2 - (width.takeIf { it > 0 } ?: 200) / 2) // Approx center
        val y = state?.windowY ?: (displayMetrics.heightPixels / 2 - (height.takeIf { it > 0} ?: 150) / 2)

        return WindowManager.LayoutParams(
            width, height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    // --- Nesting Logic (Placeholder) ---
    private fun applyNestModeVisuals(clockId: Int, clockRootView: ViewGroup, isNested: Boolean) {
        Log.d(TAG, "Applying nest visuals for $clockId: $isNested (TODO)")
        // Scale down, hide certain buttons, etc.
        // Example: clockRootView.scaleX = if (isNested) 0.7f else 1.0f
        // clockRootView.scaleY = if (isNested) 0.7f else 1.0f
        // clockRootView.findViewById<View>(R.id.buttonSettings)?.visibility = if (isNested) View.GONE else View.VISIBLE
    }
    private fun repositionNestedClocks() {
        Log.d(TAG, "Repositioning nested clocks (TODO)")
        // Logic to arrange nested clocks in columns/rows.
    }
    // --- End Nesting Logic ---


    private fun stopAllInstancesAndService() {
        Log.d(TAG, "Stopping all instances and Clock service.")
        clockViewModels.keys.toList().forEach { id -> removeClockInstance(id) }
        if (clockViewModels.isEmpty()) { stopService() }
    }

    private fun stopService() {
        Log.d(TAG, "stopService called for Clock")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isForeground = false
    }

    // --- Foreground Service & Notification (Standard) ---
    private fun startForegroundServiceIfNeeded() {
        if (isForeground) return
        val notification = createNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
            Log.d(TAG, "ClockOverlayService started in foreground.")
        } catch (e: Exception) { Log.e(TAG, "Error starting foreground service for Clock", e)}
    }

    private fun createNotification(): Notification { /* ... same as before ... */
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Floating clock(s) active")
            .setSmallIcon(R.drawable.ic_clock)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() { /* ... same as before ... */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Clock Overlay Service Channel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stateObserverJobs.values.forEach { it.cancel() }
        stateObserverJobs.clear()
        activeClockViews.keys.toList().forEach { id ->
            val view = activeClockViews.remove(id)
            clockViewInstances.remove(id)
            clockLayoutParams.remove(id)
            view?.let { if (it.isAttachedToWindow) try {windowManager.removeView(it)}catch(e:Exception){/*ignore*/} }
        }
        clockViewModels.clear()
        saveLastInstanceId()
        _viewModelStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}