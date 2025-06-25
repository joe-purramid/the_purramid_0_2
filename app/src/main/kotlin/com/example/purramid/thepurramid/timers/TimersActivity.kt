// src/main/kotlin/com/example/purramid/thepurramid/timers/TimersActivity.kt
package com.example.purramid.thepurramid.timers

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.timers.ACTION_START_STOPWATCH
import com.example.purramid.thepurramid.timers.ACTION_START_COUNTDOWN
import com.example.purramid.thepurramid.timers.EXTRA_DURATION_MS
import com.example.purramid.thepurramid.timers.EXTRA_TIMER_ID
import com.example.purramid.thepurramid.timers.TimersService
import com.example.purramid.thepurramid.timers.ui.TimersSettingsFragment
import com.example.purramid.thepurramid.timers.viewmodel.TimersViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TimersActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TimersActivity"
        // Action for the intent to show settings
        const val ACTION_SHOW_TIMER_SETTINGS = "com.example.purramid.timers.ACTION_SHOW_TIMER_SETTINGS"
    }

    private var currentTimerId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate - Intent Action: ${intent.action}")

        currentTimerId = intent.getIntExtra(EXTRA_TIMER_ID, 0)

        if (intent.action == ACTION_SHOW_TIMER_SETTINGS) {
            if (currentTimerId != 0) {
                showSettingsFragment(currentTimerId)
            } else {
                Log.e(TAG, "Cannot show settings, invalid timerId: $currentTimerId")
                finish()
            }
        } else {
            // Default action: launch a new timer service instance
            // Let the service handle getting a new instance ID from InstanceManager
            Log.d(TAG, "Launching timer service")
            startTimerService(currentTimerId, TimerType.STOPWATCH)
            finish()
        }
    }

    private fun startTimerService(timerId: Int, type: TimerType, durationMs: Long? = null) {
        Log.d(TAG, "Requesting start for TimerService, ID: $timerId, Type: $type")
        val serviceIntent = Intent(this, TimersService::class.java).apply {
            action = if (type == TimerType.COUNTDOWN) {
                ACTION_START_COUNTDOWN
            } else {
                ACTION_START_STOPWATCH
            }
            if (timerId != 0) {
                putExtra(EXTRA_TIMER_ID, timerId)
            }
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent - Action: ${intent?.action}")
        if (intent?.action == ACTION_SHOW_TIMER_SETTINGS) {
            val timerIdForSettings = intent.getIntExtra(EXTRA_TIMER_ID, 0)
            if (timerIdForSettings != 0) {
                currentTimerId = timerIdForSettings
                showSettingsFragment(timerIdForSettings)
            } else {
                Log.e(TAG, "Cannot show settings from onNewIntent, invalid timerId.")
            }
        }
    }
}