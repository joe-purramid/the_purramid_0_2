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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
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
import com.example.purramid.thepurramid.instance.InstanceManager
import com.example.purramid.thepurramid.MainActivity
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.ClockDao // Keep for restoration logic if needed directly
import com.example.purramid.thepurramid.data.db.ClockStateEntity
import com.example.purramid.thepurramid.clock.viewmodel.ClockState
import com.example.purramid.thepurramid.clock.viewmodel.ClockViewModel
import com.example.purramid.thepurramid.di.ClockPrefs // Import Hilt qualifier
import com.example.purramid.thepurramid.di.HiltViewModelFactory // If you have this helper
import com.example.purramid.thepurramid.util.dpToPx
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
import kotlin.math.abs
import kotlin.math.max
import java.lang.ref.WeakReference

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
    @Inject lateinit var instanceManager: InstanceManager
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

    private var isForeground = false

    companion object {
        private const val TAG = "ClockOverlayService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "ClockOverlayServiceChannel"
        const val MAX_CLOCKS = 4
        const val PREFS_NAME_FOR_ACTIVITY = "clock_service_state_prefs" // For Activity to read count
        const val KEY_ACTIVE_COUNT_FOR_ACTIVITY = "active_clock_count"
        const val KEY_LAST_INSTANCE_ID = "last_instance_id_clock"
        
        // Performance thresholds
        private const val MAX_UPDATE_FREQUENCY_MS = 16L
        private const val MEMORY_WARNING_THRESHOLD = 0.8f // 80% memory usage
    }
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        performanceMetrics.startSession()
        createNotificationChannel()
        loadAndRestoreClockStates()
    }

    private val performanceMetrics = PerformanceMetrics()
    private var lastUpdateTime = 0L
    private val updateThrottleMs = 16L // ~60 FPS max update rate
    
    // Object pooling for frequently created objects
    private val layoutParamsPool = mutableListOf<WindowManager.LayoutParams>()
    private val bundlePool = mutableListOf<Bundle>()

    // Memory leak prevention
    private val weakReferences = mutableListOf<WeakReference<Any>>()

    private fun updateActiveInstanceCountInPrefs() {
        servicePrefs.edit().putInt(KEY_ACTIVE_COUNT_FOR_ACTIVITY, clockViewModels.size).apply()
        Log.d(TAG, "Updated active Clock count: ${clockViewModels.size}")
    }

    private fun loadAndRestoreClockStates() {
        lifecycleScope.launch(Dispatchers.IO) {
            val startTime = SystemClock.elapsedRealtime()
            
            val persistedStates = clockDao.getAllStates()
            if (persistedStates.isNotEmpty()) {
                Log.d(TAG, "Found ${persistedStates.size} persisted clock states. Restoring...")
                persistedStates.forEach { entity ->
                    // Register the existing instance ID
                    instanceManager.registerExistingInstance(InstanceManager.CLOCK, entity.clockId)

                    launch(Dispatchers.Main) {
                        val bundle = getBundleFromPool().apply {
                            putInt(ClockViewModel.KEY_CLOCK_ID, entity.clockId)
                        }
                        initializeViewModel(entity.clockId, bundle)
                    }
                }
            }

            if (clockViewModels.isNotEmpty()) {
                startForegroundServiceIfNeeded()
            }
            
            val loadTime = SystemClock.elapsedRealtime() - startTime
            Log.d(TAG, "Clock state restoration completed in ${loadTime}ms")
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
            handleError(ClockServiceException.InstanceLimitExceeded(), "add_new_clock")
            return
        }
        
        // Request new instance ID from InstanceManager
        val newClockId = instanceManager.requestInstanceId(InstanceManager.CLOCK)
        if (newClockId == null) {
            Log.w(TAG, "No available instance IDs for new clock.")
            handleError(ClockServiceException.InvalidClockId(-1), "request_instance_id")
            return
        }
        
        safeExecute("viewmodel_init") {
            val bundle = getBundleFromPool().apply {
                putInt(ClockViewModel.KEY_CLOCK_ID, newClockId)
            }
            initializeViewModel(newClockId, bundle)
            updateActiveInstanceCountInPrefs()
        }
    }

    private fun initializeViewModel(clockId: Int, args: Bundle) {
        try {
            val startTime = SystemClock.elapsedRealtime()
            
            val viewModel = ViewModelProvider(this, viewModelFactory)[ClockViewModel::class.java]
            clockViewModels[clockId] = viewModel
            
            // Set up state observer with proper lifecycle management
            val stateJob = lifecycleScope.launch {
                viewModel.uiState.collectLatest { state ->
                    throttledUpdate {
                        updateClockDisplay(clockId, state)
                    }
                }
            }
            stateObserverJobs[clockId] = stateJob
            
            // Create and add window view
            createAndAddClockWindow(clockId, viewModel)
            
            val initTime = SystemClock.elapsedRealtime() - startTime
            Log.d(TAG, "ViewModel initialization for clock $clockId completed in ${initTime}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ViewModel for clock $clockId", e)
            cleanupClockInstance(clockId)
        }
    }

    private fun createAndAddClockWindow(clockId: Int, viewModel: ClockViewModel) {
        safeExecute("window_add") {
            // Inflate layout
            val inflater = LayoutInflater.from(this)
            val clockRootView = inflater.inflate(R.layout.clock_overlay_layout, null) as ViewGroup
            val clockView = clockRootView.findViewById<ClockView>(R.id.clockView)
            
            // Store references
            activeClockViews[clockId] = clockRootView
            clockViewInstances[clockId] = clockView
            
            // Get layout params from pool
            val params = getLayoutParamsFromPool()
            clockLayoutParams[clockId] = params
            
            // Set up touch handling
            setupWindowDragListener(clockRootView, clockId)
            
            // Add view to window manager
            windowManager.addView(clockRootView, params)
            
            // Set up control buttons
            setupControlButtons(clockRootView, clockId, viewModel)
            
            Log.d(TAG, "Clock window created for instance $clockId")
        }
    }

    private fun removeClockInstance(instanceId: Int) {
        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "Removing Clock instance ID: $instanceId")

            // Release the instance ID back to the manager
            instanceManager.releaseInstanceId(InstanceManager.CLOCK, instanceId)

            val clockView = activeClockViews.remove(instanceId)
            clockLayoutParams.remove(instanceId)
            stateObserverJobs[instanceId]?.cancel()
            stateObserverJobs.remove(instanceId)
            val viewModel = clockViewModels.remove(instanceId)

            clockView?.let {
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
            val settingsIntent = Intent(this, ClockActivity::class.java).apply {
                action = ClockActivity.ACTION_SHOW_CLOCK_SETTINGS
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
        var isResizing = false
        var initialWidth = 0
        var initialHeight = 0
        var initialDistance = 0f
        
        val touchSlopVal = ViewConfiguration.get(this).scaledTouchSlop
        val movementThreshold = dpToPx(10) // 10dp movement threshold as per universal requirements
        
        // Scale gesture detector for pinch-to-resize
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (!isMoving) {
                    isResizing = true
                    val params = clockLayoutParams[clockId] ?: return false
                    initialWidth = params.width
                    initialHeight = params.height
                    return true
                }
                return false
            }
            
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isResizing) {
                    val params = clockLayoutParams[clockId] ?: return false
                    val scaleFactor = detector.scaleFactor
                    val newWidth = (initialWidth * scaleFactor).toInt().coerceAtLeast(100) // Minimum 100px
                    val newHeight = (initialHeight * scaleFactor).toInt().coerceAtLeast(100)
                    
                    params.width = newWidth
                    params.height = newHeight
                    
                    try {
                        if (view.isAttachedToWindow) {
                            windowManager.updateViewLayout(view, params)
                            clockViewModels[clockId]?.updateWindowSize(newWidth, newHeight)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resizing window $clockId", e)
                    }
                    return true
                }
                return false
            }
            
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isResizing = false
            }
        })

        view.setOnTouchListener { _, event ->
            val params = clockLayoutParams[clockId] ?: return@setOnTouchListener false
            
            // Handle scale gestures first
            if (scaleGestureDetector.onTouchEvent(event)) {
                return@setOnTouchListener true
            }
            
            // Allow ClockView to handle its own touch events first (for hand dragging)
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
                    isResizing = false
                    true // Consume if we might drag
                }
                MotionEvent.ACTION_MOVE -> {
                    // If resizing, don't handle move events for dragging
                    if (isResizing) {
                        return@setOnTouchListener true
                    }
                    
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    
                    // Check if movement exceeds threshold
                    if (!isMoving && (abs(deltaX) > movementThreshold || abs(deltaY) > movementThreshold)) {
                        isMoving = true
                    }
                    
                    if (isMoving) {
                        val newX = initialX + deltaX.toInt()
                        val newY = initialY + deltaY.toInt()
                        
                        // Constrain to screen bounds
                        val displayMetrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
                        val maxX = displayMetrics.widthPixels - params.width
                        val maxY = displayMetrics.heightPixels - params.height
                        
                        params.x = newX.coerceIn(0, maxX)
                        params.y = newY.coerceIn(0, maxY)
                        
                        try {
                            if (view.isAttachedToWindow) {
                                windowManager.updateViewLayout(view, params)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error moving window $clockId", e)
                        }
                    }
                    true // Consume move
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Second finger down - could be start of pinch
                    if (event.pointerCount == 2) {
                        val distance = getDistance(event, 0, 1)
                        initialDistance = distance
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Finger lifted - end any ongoing gestures
                    if (event.pointerCount == 1) {
                        isResizing = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoving) {
                        clockViewModels[clockId]?.updateWindowPosition(params.x, params.y)
                    }
                    isMoving = false
                    isResizing = false
                    true
                }
                else -> false
            }
        }
    }
    
    private fun getDistance(event: MotionEvent, pointerIndex1: Int, pointerIndex2: Int): Float {
        val x1 = event.getX(pointerIndex1)
        val y1 = event.getY(pointerIndex1)
        val x2 = event.getX(pointerIndex2)
        val y2 = event.getY(pointerIndex2)
        return kotlin.math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
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

    // --- Nesting Logic Implementation ---
    private fun repositionNestedClocks() {
        Log.d(TAG, "Repositioning nested clocks in columnized layout")
        
        val nestedClocks = clockViewModels.entries
            .filter { it.value.uiState.value.isNested }
            .map { it.key }
            .toList()
        
        if (nestedClocks.isEmpty()) return
        
        val displayMetrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        val padding = dpToPx(20)
        val spacing = dpToPx(10) // Space between nested clocks
        val clockHeight = dpToPx(75) // Height of each nested clock
        
        // Calculate starting position (top-right corner)
        val startX = displayMetrics.widthPixels - dpToPx(75) - padding
        val startY = padding
        
        // Columnized stacking: arrange clocks vertically
        nestedClocks.forEachIndexed { index, clockId ->
            val clockRootView = activeClockViews[clockId] ?: return@forEachIndexed
            val params = clockLayoutParams[clockId] ?: return@forEachIndexed
            
            // Calculate Y position for columnized stacking
            val yOffset = index * (clockHeight + spacing)
            val targetY = startY + yOffset
            
            // Ensure we don't go off-screen
            val maxY = displayMetrics.heightPixels - clockHeight - padding
            val finalY = minOf(targetY, maxY)
            
            params.x = startX
            params.y = finalY
            
            // Apply the new position
            safeExecute("window_update") {
                if (clockRootView.isAttachedToWindow) {
                    windowManager.updateViewLayout(clockRootView, params)
                    clockViewModels[clockId]?.updateWindowPosition(params.x, params.y)
                }
            }
            
            Log.d(TAG, "Positioned nested clock $clockId at (${params.x}, ${params.y}) - index $index")
        }
        
        // Log stacking information
        Log.d(TAG, "Columnized stacking completed: ${nestedClocks.size} clocks arranged vertically")
    }
    
    // Enhanced nesting logic with proper columnized positioning
    private fun applyNestModeVisuals(clockId: Int, clockRootView: ViewGroup, isNested: Boolean) {
        Log.d(TAG, "Applying nest visuals for $clockId: $isNested")
        
        if (isNested) {
            // Scale down to specified sizes
            val params = clockLayoutParams[clockId] ?: return
            val isAnalog = clockViewModels[clockId]?.uiState?.value?.mode == "analog"
            
            if (isAnalog) {
                params.width = dpToPx(75)  // 75px for analog
                params.height = dpToPx(75) // 75px for analog
            } else {
                params.width = dpToPx(75)  // 75px for digital
                params.height = dpToPx(50) // 50px for digital
            }
            
            // Hide control buttons
            clockRootView.findViewById<View>(R.id.buttonPlayPause)?.visibility = View.GONE
            clockRootView.findViewById<View>(R.id.buttonReset)?.visibility = View.GONE
            clockRootView.findViewById<View>(R.id.buttonSettings)?.visibility = View.GONE
            
            // Update window layout
            safeExecute("window_update") {
                if (clockRootView.isAttachedToWindow) {
                    windowManager.updateViewLayout(clockRootView, params)
                    clockViewModels[clockId]?.updateWindowPosition(params.x, params.y)
                    clockViewModels[clockId]?.updateWindowSize(params.width, params.height)
                }
            }
            
            // Reposition all nested clocks to ensure proper columnized stacking
            repositionNestedClocks()
            
        } else {
            // Restore normal state
            val params = clockLayoutParams[clockId] ?: return
            
            // Restore default size (WRAP_CONTENT)
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            
            // Show control buttons
            clockRootView.findViewById<View>(R.id.buttonPlayPause)?.visibility = View.VISIBLE
            clockRootView.findViewById<View>(R.id.buttonReset)?.visibility = View.VISIBLE
            clockRootView.findViewById<View>(R.id.buttonSettings)?.visibility = View.VISIBLE
            
            // Update window layout
            safeExecute("window_update") {
                if (clockRootView.isAttachedToWindow) {
                    windowManager.updateViewLayout(clockRootView, params)
                    clockViewModels[clockId]?.updateWindowSize(params.width, params.height)
                }
            }
            
            // Reposition remaining nested clocks
            repositionNestedClocks()
        }
    }

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
        Log.d(TAG, "onDestroy - cleaning up resources")
        cleanupAllResources()
        super.onDestroy()
    }

    private fun cleanupAllResources() {
        // Cancel all coroutine jobs
        stateObserverJobs.values.forEach { it.cancel() }
        stateObserverJobs.clear()
        
        // Remove all window views
        activeClockViews.values.forEach { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing view during cleanup", e)
            }
        }
        activeClockViews.clear()
        
        // Clear ViewModels
        clockViewModels.clear()
        
        // Clear collections
        clockViewInstances.clear()
        clockLayoutParams.clear()
        
        // Clear object pools
        layoutParamsPool.clear()
        bundlePool.clear()
        
        // Clear weak references
        weakReferences.clear()
        
        // Clear ViewModelStore
        _viewModelStore.clear()
        
        // Performance metrics cleanup
        performanceMetrics.checkMemoryUsage()
        
        Log.d(TAG, "Resource cleanup completed")
    }
    
    private fun cleanupClockInstance(clockId: Int) {
        Log.d(TAG, "Cleaning up clock instance: $clockId")
        
        safeExecute("resource_cleanup") {
            // Cancel state observer job
            stateObserverJobs[clockId]?.cancel()
            stateObserverJobs.remove(clockId)
            
            // Remove window view
            val view = activeClockViews[clockId]
            if (view != null) {
                safeExecute("window_remove") {
                    if (view.isAttachedToWindow) {
                        windowManager.removeView(view)
                    }
                }
                activeClockViews.remove(clockId)
            }
            
            // Clear ViewModel
            clockViewModels.remove(clockId)
            clockViewInstances.remove(clockId)
            
            // Return layout params to pool
            val params = clockLayoutParams[clockId]
            if (params != null) {
                returnLayoutParamsToPool(params)
                clockLayoutParams.remove(clockId)
            }
            
            // Release instance ID
            instanceManager.releaseInstanceId(InstanceManager.CLOCK, clockId)
            
            Log.d(TAG, "Clock instance $clockId cleanup completed")
        }
    }
    
    // Weak reference management
    private fun addWeakReference(obj: Any) {
        weakReferences.add(WeakReference(obj))
        // Clean up null references periodically
        if (weakReferences.size > 100) {
            weakReferences.removeAll { it.get() == null }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // Performance monitoring class
    private inner class PerformanceMetrics {
        private var sessionStartTime = 0L
        private var updateCount = 0L
        private var totalUpdateTime = 0L
        private var maxUpdateTime = 0L
        private var minUpdateTime = Long.MAX_VALUE
        
        fun startSession() {
            sessionStartTime = SystemClock.elapsedRealtime()
            Log.d(TAG, "Performance monitoring started")
        }
        
        fun recordUpdate(durationMs: Long) {
            updateCount++
            totalUpdateTime += durationMs
            maxUpdateTime = max(maxUpdateTime, durationMs)
            minUpdateTime = minOf(minUpdateTime, durationMs)
            
            // Log performance warnings
            if (durationMs > 33) { // More than 30 FPS threshold
                Log.w(TAG, "Slow update detected: ${durationMs}ms")
            }
            
            // Periodic performance report
            if (updateCount % 100 == 0L) {
                val avgUpdateTime = totalUpdateTime / updateCount
                Log.d(TAG, "Performance report: avg=${avgUpdateTime}ms, max=${maxUpdateTime}ms, min=${minUpdateTime}ms")
            }
        }
        
        fun checkMemoryUsage() {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val memoryUsage = usedMemory.toFloat() / runtime.maxMemory()
            
            if (memoryUsage > MEMORY_WARNING_THRESHOLD) {
                Log.w(TAG, "High memory usage: ${(memoryUsage * 100).toInt()}%")
                // Trigger garbage collection if needed
                if (memoryUsage > 0.9f) {
                    System.gc()
                }
            }
        }
    }

    // Object pooling methods
    private fun getLayoutParamsFromPool(): WindowManager.LayoutParams {
        return if (layoutParamsPool.isNotEmpty()) {
            layoutParamsPool.removeAt(0)
        } else {
            WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                gravity = Gravity.TOP or Gravity.START
            }
        }
    }
    
    private fun returnLayoutParamsToPool(params: WindowManager.LayoutParams) {
        if (layoutParamsPool.size < 10) { // Limit pool size
            layoutParamsPool.add(params)
        }
    }
    
    private fun getBundleFromPool(): Bundle {
        return if (bundlePool.isNotEmpty()) bundlePool.removeAt(0) else Bundle()
    }
    
    private fun returnBundleToPool(bundle: Bundle) {
        if (bundlePool.size < 20) { // Limit pool size
            bundle.clear()
            bundlePool.add(bundle)
        }
    }
    
    // Throttled update method
    private fun throttledUpdate(updateAction: () -> Unit) {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastUpdateTime >= updateThrottleMs) {
            val startTime = SystemClock.elapsedRealtime()
            updateAction()
            val updateTime = SystemClock.elapsedRealtime() - startTime
            performanceMetrics.recordUpdate(updateTime)
            lastUpdateTime = currentTime
        }
    }

    // Error handling improvements
    sealed class ClockServiceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class InstanceLimitExceeded : ClockServiceException("Maximum number of clocks reached")
        class InvalidClockId(clockId: Int) : ClockServiceException("Invalid clock ID: $clockId")
        class WindowManagerError(operation: String, cause: Throwable? = null) : ClockServiceException("Window manager error during $operation", cause)
        class ViewModelInitializationError(clockId: Int, cause: Throwable? = null) : ClockServiceException("Failed to initialize ViewModel for clock $clockId", cause)
        class DatabaseError(operation: String, cause: Throwable? = null) : ClockServiceException("Database error during $operation", cause)
        class ResourceCleanupError(operation: String, cause: Throwable? = null) : ClockServiceException("Error during resource cleanup: $operation", cause)
    }
    
    private fun handleError(exception: ClockServiceException, context: String = "") {
        val errorMessage = when (exception) {
            is ClockServiceException.InstanceLimitExceeded -> "Maximum number of clocks (${MAX_CLOCKS}) reached"
            is ClockServiceException.InvalidClockId -> "Invalid clock configuration"
            is ClockServiceException.WindowManagerError -> "Display error occurred"
            is ClockServiceException.ViewModelInitializationError -> "Failed to initialize clock"
            is ClockServiceException.DatabaseError -> "Data storage error"
            is ClockServiceException.ResourceCleanupError -> "System cleanup error"
        }
        
        Log.e(TAG, "Error in $context: ${exception.message}", exception)
        
        // Show user-friendly error message
        showErrorToUser(errorMessage)
        
        // Attempt recovery based on error type
        attemptErrorRecovery(exception)
    }
    
    private fun showErrorToUser(message: String) {
        // Use Snackbar instead of Toast for better UX
        try {
            val mainActivity = findMainActivity()
            mainActivity?.let { activity ->
                activity.runOnUiThread {
                    com.google.android.material.snackbar.Snackbar.make(
                        activity.findViewById(android.R.id.content),
                        message,
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show error message to user", e)
        }
    }
    
    private fun findMainActivity(): MainActivity? {
        return try {
            val activities = (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .getRunningTasks(1)
                .firstOrNull()
                ?.topActivity
            
            if (activities?.className?.contains("MainActivity") == true) {
                activities as? MainActivity
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding MainActivity", e)
            null
        }
    }
    
    private fun attemptErrorRecovery(exception: ClockServiceException) {
        when (exception) {
            is ClockServiceException.InstanceLimitExceeded -> {
                // No recovery needed, just inform user
                Log.i(TAG, "Instance limit reached, no recovery needed")
            }
            is ClockServiceException.InvalidClockId -> {
                // Clean up invalid instance
                val clockId = exception.message?.substringAfter(": ")?.toIntOrNull()
                if (clockId != null) {
                    cleanupClockInstance(clockId)
                }
            }
            is ClockServiceException.WindowManagerError -> {
                // Attempt to recreate window
                Log.i(TAG, "Attempting window recreation after error")
                recreateAllWindows()
            }
            is ClockServiceException.ViewModelInitializationError -> {
                // Clean up failed ViewModel
                val clockId = exception.message?.substringAfter("clock ")?.substringBefore(" ")?.toIntOrNull()
                if (clockId != null) {
                    cleanupClockInstance(clockId)
                }
            }
            is ClockServiceException.DatabaseError -> {
                // Attempt database recovery
                Log.i(TAG, "Attempting database recovery")
                attemptDatabaseRecovery()
            }
            is ClockServiceException.ResourceCleanupError -> {
                // Force cleanup
                Log.w(TAG, "Forcing resource cleanup after error")
                forceCleanup()
            }
        }
    }
    
    private fun recreateAllWindows() {
        lifecycleScope.launch {
            try {
                val clockIds = clockViewModels.keys.toList()
                clockIds.forEach { clockId ->
                    val viewModel = clockViewModels[clockId]
                    if (viewModel != null) {
                        // Remove old window
                        val oldView = activeClockViews[clockId]
                        if (oldView != null && oldView.isAttachedToWindow) {
                            try {
                                windowManager.removeView(oldView)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error removing old view during recreation", e)
                            }
                        }
                        
                        // Create new window
                        createAndAddClockWindow(clockId, viewModel)
                    }
                }
                Log.d(TAG, "Window recreation completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during window recreation", e)
                handleError(ClockServiceException.WindowManagerError("recreation", e))
            }
        }
    }
    
    private fun attemptDatabaseRecovery() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Attempt to reinitialize database connection
                val testQuery = clockDao.getActiveInstanceCount()
                Log.d(TAG, "Database recovery successful, active instances: $testQuery")
            } catch (e: Exception) {
                Log.e(TAG, "Database recovery failed", e)
                handleError(ClockServiceException.DatabaseError("recovery", e))
            }
        }
    }
    
    private fun forceCleanup() {
        try {
            // Force garbage collection
            System.gc()
            
            // Clear all collections
            clockViewModels.clear()
            activeClockViews.clear()
            clockViewInstances.clear()
            clockLayoutParams.clear()
            stateObserverJobs.clear()
            layoutParamsPool.clear()
            bundlePool.clear()
            weakReferences.clear()
            
            Log.d(TAG, "Force cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during force cleanup", e)
        }
    }
    
    // Enhanced error handling for critical operations
    private fun safeExecute(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            when (operation) {
                "window_add" -> handleError(ClockServiceException.WindowManagerError("add", e))
                "window_remove" -> handleError(ClockServiceException.WindowManagerError("remove", e))
                "window_update" -> handleError(ClockServiceException.WindowManagerError("update", e))
                "viewmodel_init" -> handleError(ClockServiceException.ViewModelInitializationError(-1, e))
                "database_operation" -> handleError(ClockServiceException.DatabaseError(operation, e))
                "resource_cleanup" -> handleError(ClockServiceException.ResourceCleanupError(operation, e))
                else -> Log.e(TAG, "Unhandled error during $operation", e)
            }
        }
    }
}