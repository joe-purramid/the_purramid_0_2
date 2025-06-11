// src/main/kotlin/com/example/purramid/thepurramid/timers/TimersActivity.kt
package com.example.purramid.thepurramid.timers

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.timers.service.ACTION_START_STOPWATCH
import com.example.purramid.thepurramid.timers.service.EXTRA_DURATION_MS
import com.example.purramid.thepurramid.timers.service.EXTRA_TIMER_ID
import com.example.purramid.thepurramid.timers.service.TimersService
import com.example.purramid.thepurramid.timers.ui.TimersSettingsFragment // Import settings fragment
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID // Using UUID for potential future flexibility
import java.util.concurrent.atomic.AtomicInteger

@AndroidEntryPoint
class TimersActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TimersActivity"
        // Action for the intent to show settings
        const val ACTION_SHOW_TIMER_SETTINGS = "com.example.purramid.timers.ACTION_SHOW_TIMER_SETTINGS"

        // Simple counter for demo purposes. Replace with robust ID management.
        private val timerIdCounter = AtomicInteger(1) // Start from 1

        // This should ideally come from a shared preference or DB to ensure uniqueness across app sessions
        fun getNextTimerId(): Int {
            return timerIdCounter.getAndIncrement()
        }
    }

    private var currentTimerId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This activity might not need its own layout if it only launches service/fragment
        // setContentView(R.layout.activity_timers) // Optional: if you want a host layout
        Log.d(TAG, "onCreate - Intent Action: ${intent.action}")

        currentTimerId = intent.getIntExtra(EXTRA_TIMER_ID, 0)

        if (intent.action == ACTION_SHOW_TIMER_SETTINGS) {
            if (currentTimerId != 0) {
                showSettingsFragment(currentTimerId)
            } else {
                Log.e(TAG, "Cannot show settings, invalid timerId: $currentTimerId")
                finish() // Close if ID is invalid for settings
            }
        } else {
            // Default action: launch a new timer service instance
            if (currentTimerId == 0) { // Only generate new ID if one wasn't passed
                currentTimerId = getNextTimerId()
            }
            Log.d(TAG, "Launching/ensuring service for timerId: $currentTimerId")
            startTimerService(currentTimerId, TimerType.STOPWATCH) // Default to stopwatch
            finish() // Finish after launching the service
        }
    }

    private fun startTimerService(timerId: Int, type: TimerType, durationMs: Long? = null) {
        Log.d(TAG, "Requesting start for TimerService, ID: $timerId, Type: $type")
        val serviceIntent = Intent(this, TimersService::class.java).apply {
            action = if (type == TimerType.COUNTDOWN) {
                com.example.purramid.thepurramid.timers.service.ACTION_START_COUNTDOWN
            } else {
                com.example.purramid.thepurramid.timers.service.ACTION_START_STOPWATCH
            }
            putExtra(EXTRA_TIMER_ID, timerId)
            durationMs?.let { putExtra(EXTRA_DURATION_MS, it) }
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun showSettingsFragment(timerId: Int) {
        if (supportFragmentManager.findFragmentByTag(TimersSettingsFragment.TAG) == null) {
            Log.d(TAG, "Showing settings fragment for timerId: $timerId")
            TimersSettingsFragment.newInstance(timerId).show(
                supportFragmentManager, TimersSettingsFragment.TAG
            )
        }
    }

    // Handle new intent if Activity is already running and settings are requested
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent
        Log.d(TAG, "onNewIntent - Action: ${intent?.action}")
        if (intent?.action == ACTION_SHOW_TIMER_SETTINGS) {
            val timerIdForSettings = intent.getIntExtra(EXTRA_TIMER_ID, 0)
            if (timerIdForSettings != 0) {
                currentTimerId = timerIdForSettings // Update current ID if settings for specific timer
                showSettingsFragment(timerIdForSettings)
            } else {
                Log.e(TAG, "Cannot show settings from onNewIntent, invalid timerId.")
            }
        } else {
            // If launched again without specific action, and it's not for settings,
            // it might be an attempt to launch a new timer.
            // The current logic in onCreate might lead to just finishing.
            // Consider how multiple launches of TimersActivity should behave.
            // For now, if it's not for settings, onCreate's logic of launching service and finishing will run.
        }
    }
}