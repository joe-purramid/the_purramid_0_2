// TimersService.kt
package com.example.purramid.thepurramid.timers

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.os.Bundle // Needed for SavedStateViewModelFactory args
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast // For Settings placeholder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import com.example.purramid.thepurramid.MainActivity
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.timers.TimerState
import com.example.purramid.thepurramid.timers.TimerType
import com.example.purramid.thepurramid.timers.viewmodel.TimersViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

// Service Actions (Ensure these are defined or imported)
const val ACTION_START_STOPWATCH = "com.example.purramid.timers.ACTION_START_STOPWATCH"
const val ACTION_START_COUNTDOWN = "com.example.purramid.timers.ACTION_START_COUNTDOWN"
const val ACTION_STOP_TIMER_SERVICE = "com.example.purramid.timers.ACTION_STOP_TIMER_SERVICE" // Renamed for clarity
const val EXTRA_TIMER_ID = TimersViewModel.KEY_TIMER_ID
const val EXTRA_DURATION_MS = "com.example.purramid.timers.EXTRA_DURATION_MS"

@AndroidEntryPoint
class TimersService : LifecycleService(), ViewModelStoreOwner {

    @Inject lateinit var windowManager: WindowManager
    // Inject Hilt's factory; no need for custom factory if using SavedStateViewModelFactory
    // @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    private val viewModelStore = ViewModelStore()
    override fun getViewModelStore(): ViewModelStore = viewModelStore
    private lateinit var viewModel: TimersViewModel

    private var overlayView: View? = null
    private var timerId: Int = 0
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isViewAdded = false
    private var stateObserverJob: Job? = null
    private var currentInflatedLayoutId: Int = 0 // Track which layout is inflated

    // --- Cached Views (Common and Specific) ---
    // Common
    private var digitalTimeTextView: TextView? = null
    private var playPauseButton: ImageView? = null
    private var settingsButton: ImageView? = null
    private var closeButton: TextView? = null
    // Countdown Specific
    private var centisecondsTextView: TextView? = null
    private var resetButtonCountdown: ImageView? = null // Renamed for clarity
    // Stopwatch Specific
    private var lapResetButtonStopwatch: Button? = null // Renamed for clarity
    private var lapTimesLayout: LinearLayout? = null
    private var lapTimeTextViews = mutableListOf<TextView>()
    private var noLapsTextView: TextView? = null

    // Touch handling
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isMoving = false
    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    companion object {
        private const val TAG = "TimersService"
        private const val NOTIFICATION_ID = 5
        private const val CHANNEL_ID = "TimersServiceChannel"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        // ViewModel initialization moved to onStartCommand to ensure timerId is available
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        val intentTimerId = intent?.getIntExtra(EXTRA_TIMER_ID, 0) ?: 0
        Log.d(TAG, "onStartCommand: Action: $action, ID: $intentTimerId")

        if (intentTimerId <= 0 && action != ACTION_STOP_TIMER_SERVICE) {
            Log.e(TAG, "Invalid or missing Timer ID ($intentTimerId) for action '$action'. Stopping service.")
            stopService()
            return START_NOT_STICKY // Don't restart if ID is invalid
        }

        // Initialize ViewModel if needed (first start or ID change)
        if (!::viewModel.isInitialized || this.timerId != intentTimerId) {
            this.timerId = intentTimerId
            // Use default factory which Hilt provides, including SavedStateHandle support
            viewModel = ViewModelProvider(this)[TimersViewModel::class.java]
            Log.d(TAG, "ViewModel initialized for timerId: $timerId")
            observeViewModelState() // Start observing the correct ViewModel instance
        }

        // Handle actions
        when (action) {
            ACTION_START_STOPWATCH -> {
                viewModel.setTimerType(TimerType.STOPWATCH)
                startForegroundServiceIfNeeded()
                // Use lifecycleScope to ensure addOverlayViewIfNeeded runs after state is potentially updated
                lifecycleScope.launch { addOverlayViewIfNeeded() }
            }
            ACTION_START_COUNTDOWN -> {
                val duration = intent.getLongExtra(EXTRA_DURATION_MS, 60000L)
                viewModel.setTimerType(TimerType.COUNTDOWN)
                viewModel.setInitialDuration(duration) // Set duration
                startForegroundServiceIfNeeded()
                lifecycleScope.launch { addOverlayViewIfNeeded() }
            }
            ACTION_STOP_TIMER_SERVICE -> {
                stopService() // Handle explicit stop request
            }
        }
        return START_STICKY
    }

    private fun observeViewModelState() {
        stateObserverJob?.cancel() // Cancel previous job
        stateObserverJob = lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                Log.d(TAG, "Collecting State: Type=${state.type}, Running=${state.isRunning}, Millis=${state.currentMillis}")
                // Check if the layout needs to change based on the state's type
                val requiredLayoutId = when (state.type) {
                    TimerType.STOPWATCH -> R.layout.view_floating_timer_stopwatch
                    TimerType.COUNTDOWN -> R.layout.view_floating_timer_countdown
                }
                if (overlayView == null || currentInflatedLayoutId != requiredLayoutId) {
                    Log.d(TAG, "State requires layout change. Current: $currentInflatedLayoutId, Required: $requiredLayoutId")
                    addOverlayViewIfNeeded() // This will inflate the correct layout
                } else {
                    // Layout is correct, just update the views
                    updateOverlayViews(state)
                }
                // Update layout params (position/size) if they differ from state
                updateLayoutParamsIfNeeded(state)
            }
        }
        Log.d(TAG, "Started observing ViewModel state for timer $timerId")
    }

    private fun addOverlayViewIfNeeded() {
        // This function now primarily handles inflation/re-inflation
        lifecycleScope.launch(Dispatchers.Main) { // Ensure UI operations on Main thread
            val currentState = viewModel.uiState.value // Get the latest state
            val requiredLayoutId = when (currentState.type) {
                TimerType.STOPWATCH -> R.layout.view_floating_timer_stopwatch
                TimerType.COUNTDOWN -> R.layout.view_floating_timer_countdown
            }

            // If view exists but layout is wrong, remove the old one first
            if (overlayView != null && currentInflatedLayoutId != requiredLayoutId) {
                removeOverlayView()
            }

            // Create/Inflate if view is null
            if (overlayView == null) {
                layoutParams = createDefaultLayoutParams()
                // Apply saved position/size from state *before* adding
                applyStateToLayoutParams(currentState, layoutParams!!)

                val inflater = LayoutInflater.from(this@TimersService)
                overlayView = inflater.inflate(requiredLayoutId, null)
                currentInflatedLayoutId = requiredLayoutId // Track inflated layout

                cacheViews() // Find and cache views from the newly inflated layout
                setupListeners() // Setup listeners for the cached views
                setupWindowDragListener() // Add drag listener to the root overlay view

                try {
                    if (!isViewAdded) { // Check isViewAdded before adding
                        windowManager.addView(overlayView, layoutParams)
                        isViewAdded = true
                        Log.d(TAG, "Timer overlay view added (Layout ID: $requiredLayoutId).")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding timer overlay view", e)
                    cleanupViewReferences() // Reset view state if add failed
                }
            }
            // Update the view with the current state data immediately after ensuring it's added/correct
            updateOverlayViews(currentState)
        }
    }

    // Cache view references after inflating a layout
    private fun cacheViews() {
        if (overlayView == null) return
        digitalTimeTextView = overlayView?.findViewById(R.id.digitalTimeTextView)
        playPauseButton = overlayView?.findViewById(R.id.playPauseButton)
        settingsButton = overlayView?.findViewById(R.id.settingsButton)
        closeButton = overlayView?.findViewById(R.id.closeButton)
        // Type specific views
        centisecondsTextView = overlayView?.findViewById(R.id.centisecondsTextView)
        resetButtonCountdown = overlayView?.findViewById(R.id.resetButton)
        lapResetButtonStopwatch = overlayView?.findViewById(R.id.lapResetButton)
        lapTimesLayout = overlayView?.findViewById(R.id.lapTimesLayout)
        noLapsTextView = overlayView?.findViewById(R.id.noLapsTextView)
        lapTimeTextViews.clear()
        lapTimeTextViews.add(overlayView?.findViewById(R.id.lapTime1TextView) ?: TextView(this)) // Add safely
        lapTimeTextViews.add(overlayView?.findViewById(R.id.lapTime2TextView) ?: TextView(this))
        lapTimeTextViews.add(overlayView?.findViewById(R.id.lapTime3TextView) ?: TextView(this))
        lapTimeTextViews.add(overlayView?.findViewById(R.id.lapTime4TextView) ?: TextView(this))
        lapTimeTextViews.add(overlayView?.findViewById(R.id.lapTime5TextView) ?: TextView(this))
        // Remove placeholder TextViews if they weren't found
        lapTimeTextViews.removeAll { it.id == View.NO_ID }
    }

    // Setup listeners for cached views
    private fun setupListeners() {
        playPauseButton?.setOnClickListener { viewModel.togglePlayPause() }
        closeButton?.setOnClickListener { stopService() }
        settingsButton?.setOnClickListener { openSettings() }
        lapResetButtonStopwatch?.setOnClickListener { // Stopwatch Lap/Reset
            if (viewModel.uiState.value.isRunning) viewModel.addLap() else viewModel.resetTimer()
        }
        resetButtonCountdown?.setOnClickListener { // Countdown Reset
            viewModel.resetTimer()
        }
    }

    // Update the UI elements based on the current state
    private fun updateOverlayViews(state: TimerState) {
        if (overlayView == null || !isViewAdded) return // Don't update if view isn't ready

        // Ensure correct layout type is inflated before updating views
        val requiredLayoutId = when (state.type) {
            TimerType.STOPWATCH -> R.layout.view_floating_timer_stopwatch
            TimerType.COUNTDOWN -> R.layout.view_floating_timer_countdown
        }
        if (currentInflatedLayoutId != requiredLayoutId) {
            Log.w(TAG, "Timer state update received, but layout mismatch. Waiting for re-inflation.")
            return // Skip update if layout is wrong, wait for addOverlayViewIfNeeded to fix it
        }

        // Set background color of the root overlay view
        overlayView?.setBackgroundColor(state.overlayColor)

        // Determine text color based on background luminance for contrast
        val textColor = if (Color.luminance(state.overlayColor) > 0.5) Color.BLACK else Color.WHITE

        // Update Time Display & Text Color
        val showCenti = state.showCentiseconds // Check setting
        val timeStr = formatTime(state.currentMillis, showCenti)

        digitalTimeTextView?.text = timeStr.substringBeforeLast('.')
        digitalTimeTextView?.setTextColor(textColor)

        centisecondsTextView?.let {
            it.text = if (showCenti && timeStr.contains('.')) ".${timeStr.substringAfterLast('.')}" else ""
            it.visibility = if (showCenti && state.type == TimerType.COUNTDOWN) View.VISIBLE else View.GONE
            it.setTextColor(textColor)
        }

        // Update Play/Pause Button state and icon
        playPauseButton?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN) // Tint the icon
        playPauseButton?.setImageResource(if (state.isRunning) R.drawable.ic_pause else R.drawable.ic_play)
        playPauseButton?.contentDescription = getString(if (state.isRunning) R.string.pause else R.string.play)

        // Update Close Button (Text color)
        closeButton?.setTextColor(textColor) // Assuming closeButton is a TextView with '✕'

        // Update Stopwatch Specific UI & Text Colors
        if (state.type == TimerType.STOPWATCH) {
            lapResetButtonStopwatch?.setTextColor(textColor) // For Button text
            // Consider tinting button background or using MaterialButton with appropriate styling for contrast
            lapResetButtonStopwatch?.text = getString(if (state.isRunning) R.string.lap else R.string.reset)
            lapResetButtonStopwatch?.isEnabled = state.isRunning || state.currentMillis > 0L

            val hasLaps = state.laps.isNotEmpty()
            lapTimesLayout?.visibility = if (hasLaps) View.VISIBLE else View.GONE
            noLapsTextView?.visibility = if (!hasLaps) View.VISIBLE else View.GONE
            noLapsTextView?.setTextColor(textColor)

            val reversedLaps = state.laps.reversed()
            lapTimeTextViews.forEachIndexed { index, textView ->
                if (index < reversedLaps.size) {
                    textView.text = "${state.laps.size - index}. ${formatTime(reversedLaps[index], true)}"
                    textView.visibility = View.VISIBLE
                    textView.setTextColor(textColor)
                } else {
                    textView.visibility = View.GONE
                }
            }
        }

        // Update Stopwatch Specific UI & Text Colors
        if (state.type == TimerType.STOPWATCH) {
            lapResetButtonStopwatch?.setTextColor(textColor) // For Button text
            // Consider tinting button background or using MaterialButton with appropriate styling for contrast
            lapResetButtonStopwatch?.text = getString(if (state.isRunning) R.string.lap else R.string.reset)
            lapResetButtonStopwatch?.isEnabled = state.isRunning || state.currentMillis > 0L

            val hasLaps = state.laps.isNotEmpty()
            lapTimesLayout?.visibility = if (hasLaps) View.VISIBLE else View.GONE
            noLapsTextView?.visibility = if (!hasLaps) View.VISIBLE else View.GONE
            noLapsTextView?.setTextColor(textColor)

            val reversedLaps = state.laps.reversed()
            lapTimeTextViews.forEachIndexed { index, textView ->
                if (index < reversedLaps.size) {
                    textView.text = "${state.laps.size - index}. ${formatTime(reversedLaps[index], true)}"
                    textView.visibility = View.VISIBLE
                    textView.setTextColor(textColor)
                } else {
                    textView.visibility = View.GONE
                }
            }
        }

        // Update Countdown Specific UI & Icon Tints
        if (state.type == TimerType.COUNTDOWN) {
            resetButtonCountdown?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
            resetButtonCountdown?.isEnabled = !state.isRunning && (state.currentMillis != state.initialDurationMillis || state.currentMillis == 0L)
        }

    // Apply saved state to LayoutParams
    private fun applyStateToLayoutParams(state: TimerState, params: WindowManager.LayoutParams) {
        params.x = state.windowX
        params.y = state.windowY
        params.width = if (state.windowWidth > 0) state.windowWidth else WindowManager.LayoutParams.WRAP_CONTENT
        params.height = if (state.windowHeight > 0) state.windowHeight else WindowManager.LayoutParams.WRAP_CONTENT
        // Ensure gravity is correct for absolute positioning if coords are non-zero
        if (state.windowX != 0 || state.windowY != 0) {
            params.gravity = Gravity.TOP or Gravity.START
        } else {
            params.gravity = Gravity.CENTER // Default to center if no position saved
        }
    }

    // Update layout params only if changed
    private fun updateLayoutParamsIfNeeded(state: TimerState) {
        if (layoutParams == null || !isViewAdded || overlayView == null) return
        var needsUpdate = false
        if (layoutParams?.x != state.windowX || layoutParams?.y != state.windowY) {
            layoutParams?.x = state.windowX
            layoutParams?.y = state.windowY
            layoutParams?.gravity = Gravity.TOP or Gravity.START // Use absolute position
            needsUpdate = true
        }
        val newWidth = if (state.windowWidth > 0) state.windowWidth else WindowManager.LayoutParams.WRAP_CONTENT
        val newHeight = if (state.windowHeight > 0) state.windowHeight else WindowManager.LayoutParams.WRAP_CONTENT
        if (layoutParams?.width != newWidth || layoutParams?.height != newHeight) {
            layoutParams?.width = newWidth
            layoutParams?.height = newHeight
            needsUpdate = true
        }

        if (needsUpdate) {
            try {
                windowManager.updateViewLayout(overlayView, layoutParams)
            } catch (e: Exception) { Log.e(TAG, "Error updating layout params from state", e) }
        }
    }

    private fun removeOverlayView() {
        // Ensure execution on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "removeOverlayView called from non-UI thread. Posting to handler.")
            Handler(Looper.getMainLooper()).post { removeOverlayView() }
            return
        }
        overlayView?.let {
            if (isViewAdded && it.isAttachedToWindow) {
                try {
                    windowManager.removeView(it)
                    isViewAdded = false
                    Log.d(TAG, "Timer overlay view removed.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing timer overlay view", e)
                }
            }
        }
        cleanupViewReferences()
    }

    private fun cleanupViewReferences() {
        overlayView = null
        layoutParams = null // Also clear params when view is removed
        isViewAdded = false
        currentInflatedLayoutId = 0
        // Clear cached views
        digitalTimeTextView = null
        centisecondsTextView = null
        playPauseButton = null
        lapResetButtonStopwatch = null
        resetButtonCountdown = null
        settingsButton = null
        closeButton = null
        lapTimesLayout = null
        lapTimeTextViews.clear()
        noLapsTextView = null
    }


    // Format milliseconds to HH:MM:SS.cc or MM:SS.cc or SS.cc
    private fun formatTime(millis: Long, includeCentiseconds: Boolean): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = totalSeconds % 60
        val centi = (millis % 1000) / 10

        val timeString = when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
            minutes > 0 -> String.format("%02d:%02d", minutes, seconds)
            else -> String.format("%02d", seconds)
        }

        return if (includeCentiseconds) {
            String.format("%s.%02d", timeString, centi)
        } else {
            timeString
        }
    }


    private fun openSettings() {
        Toast.makeText(this, "Timer Settings: Coming Soon!", Toast.LENGTH_SHORT).show()
        // TODO: Implement launching TimersSettingsActivity/Fragment
        // val intent = Intent(this, TimersSettingsActivity::class.java)
        // intent.putExtra(EXTRA_TIMER_ID, timerId)
        // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // startActivity(intent)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWindowDragListener() {
        overlayView?.setOnTouchListener { _, event ->
            layoutParams?.let { params ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false // Reset moving flag
                        return@setOnTouchListener true // Consume event if we might drag
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val currentX = event.rawX
                        val currentY = event.rawY
                        val deltaX = currentX - initialTouchX
                        val deltaY = currentY - initialTouchY

                        // Start moving only if displacement exceeds touch slop
                        if (!isMoving && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                            isMoving = true
                        }

                        if (isMoving) {
                            params.x = initialX + deltaX.toInt()
                            params.y = initialY + deltaY.toInt()
                            try {
                                windowManager.updateViewLayout(overlayView, params)
                            } catch (e: Exception) { Log.e(TAG, "Error updating layout on move", e) }
                        }
                        return@setOnTouchListener true // Consume move events (even if not exceeding slop yet)
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isMoving) {
                            // Save final position only if a move actually occurred
                            viewModel.updateWindowPosition(params.x, params.y)
                            isMoving = false // Reset flag
                        }
                        // Don't consume UP if no move occurred, to allow button clicks to pass through
                        // Let the button's own OnClickListener handle the tap.
                        return@setOnTouchListener isMoving
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        isMoving = false // Reset flag on cancel
                        return@setOnTouchListener false
                    }
                }
            }
            false // Don't consume if params are null
        }
    }

    // --- Service Lifecycle & Foreground ---
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stateObserverJob?.cancel()
        removeOverlayView()
        viewModelStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private var isForeground = false

    private fun startForegroundServiceIfNeeded() {
        if (isForeground) return
        val notification = createNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
            Log.d(TAG, "TimersService started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service for Timers", e)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Purramid Timer Active")
            .setContentText("Timer or stopwatch is running.")
            .setSmallIcon(R.drawable.ic_timer) // Use timer icon
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Timers Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}