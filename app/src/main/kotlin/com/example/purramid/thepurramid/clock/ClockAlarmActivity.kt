// ClockAlarmActivity.kt
package com.example.purramid.thepurramid.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.ClockAlarmDao
import com.example.purramid.thepurramid.data.db.ClockAlarmEntity
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ClockAlarmActivity : AppCompatActivity() {

    @Inject lateinit var alarmDao: ClockAlarmDao
    
    private lateinit var timePicker: TimePicker
    private lateinit var labelEditText: EditText
    private lateinit var soundSwitch: Switch
    private lateinit var vibrationSwitch: Switch
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button
    
    private var clockId: Int = -1
    private var editingAlarmId: Long = -1
    
    companion object {
        private const val TAG = "ClockAlarmActivity"
        const val EXTRA_CLOCK_ID = "clock_id"
        const val EXTRA_ALARM_ID = "alarm_id" // For editing existing alarms
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clock_alarm)
        
        clockId = intent.getIntExtra(EXTRA_CLOCK_ID, -1)
        editingAlarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1)
        
        if (clockId == -1) {
            Snackbar.make(findViewById(android.R.id.content), "Invalid clock ID", Snackbar.LENGTH_SHORT).show()
            finish()
            return
        }
        
        initializeViews()
        setupListeners()
        
        if (editingAlarmId != -1L) {
            loadExistingAlarm()
        }
    }
    
    private fun initializeViews() {
        timePicker = findViewById(R.id.timePicker)
        labelEditText = findViewById(R.id.labelEditText)
        soundSwitch = findViewById(R.id.soundSwitch)
        vibrationSwitch = findViewById(R.id.vibrationSwitch)
        saveButton = findViewById(R.id.saveButton)
        cancelButton = findViewById(R.id.cancelButton)
        
        // Set 24-hour format
        timePicker.setIs24HourView(true)
    }
    
    private fun setupListeners() {
        saveButton.setOnClickListener {
            saveAlarm()
        }
        
        cancelButton.setOnClickListener {
            finish()
        }
    }
    
    private fun loadExistingAlarm() {
        lifecycleScope.launch {
            val alarm = alarmDao.getAlarmById(editingAlarmId)
            alarm?.let {
                timePicker.hour = it.time.hour
                timePicker.minute = it.time.minute
                labelEditText.setText(it.label)
                soundSwitch.isChecked = it.soundEnabled
                vibrationSwitch.isChecked = it.vibrationEnabled
            }
        }
    }
    
    private fun saveAlarm() {
        val time = LocalTime.of(timePicker.hour, timePicker.minute)
        val label = labelEditText.text.toString()
        val soundEnabled = soundSwitch.isChecked
        val vibrationEnabled = vibrationSwitch.isChecked
        
        lifecycleScope.launch {
            try {
                val alarm = ClockAlarmEntity(
                    alarmId = if (editingAlarmId != -1L) editingAlarmId else 0,
                    clockId = clockId,
                    time = time,
                    label = label,
                    soundEnabled = soundEnabled,
                    vibrationEnabled = vibrationEnabled
                )
                
                if (editingAlarmId != -1L) {
                    alarmDao.updateAlarm(alarm)
                } else {
                    alarmDao.insertAlarm(alarm)
                }
                
                // Schedule the alarm
                scheduleAlarm(alarm)
                
                Snackbar.make(findViewById(android.R.id.content), "Alarm saved", Snackbar.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving alarm", e)
                Snackbar.make(findViewById(android.R.id.content), "Error saving alarm", Snackbar.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun scheduleAlarm(alarm: ClockAlarmEntity) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // Create intent for our custom alarm receiver
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("alarm_id", alarm.alarmId)
            putExtra("clock_id", alarm.clockId)
            putExtra("label", alarm.label)
            putExtra("sound_enabled", alarm.soundEnabled)
            putExtra("vibration_enabled", alarm.vibrationEnabled)
            putExtra("time_zone_id", alarm.timeZoneId ?: ZoneId.systemDefault().id)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            alarm.alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Calculate trigger time based on timezone
        val triggerTime = calculateTriggerTime(alarm)
        
        // Use system AlarmManager for reliable scheduling
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
        
        Log.d(TAG, "Scheduled alarm ${alarm.alarmId} for ${alarm.time} in timezone ${alarm.timeZoneId}")
        
        // Show confirmation to user
        showAlarmConfirmation(alarm)
    }
    
    private fun calculateTriggerTime(alarm: ClockAlarmEntity): Long {
        val timeZone = ZoneId.of(alarm.timeZoneId ?: ZoneId.systemDefault().id)
        val now = ZonedDateTime.now(timeZone)
        val today = now.toLocalDate()
        val alarmDateTime = LocalDateTime.of(today, alarm.time)
        var alarmZonedDateTime = ZonedDateTime.of(alarmDateTime, timeZone)
        
        // If alarm time has passed today, schedule for tomorrow
        if (alarmZonedDateTime.isBefore(now)) {
            alarmZonedDateTime = alarmZonedDateTime.plusDays(1)
        }
        
        // Convert to system time (milliseconds since epoch)
        return alarmZonedDateTime.toInstant().toEpochMilli()
    }
    
    private fun showAlarmConfirmation(alarm: ClockAlarmEntity) {
        val timeString = String.format("%02d:%02d", alarm.time.hour, alarm.time.minute)
        val timeZoneName = ZoneId.of(alarm.timeZoneId ?: ZoneId.systemDefault().id).id
        
        val message = if (alarm.label.isNotEmpty()) {
            "Alarm '$alarm.label' set for $timeString ($timeZoneName)"
        } else {
            "Alarm set for $timeString ($timeZoneName)"
        }
        
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show()
    }
    
    // Enhanced alarm management
    private fun loadExistingAlarms() {
        lifecycleScope.launch {
            try {
                val alarms = alarmDao.getAlarmsForClock(clockId).first()
                // Update UI to show existing alarms
                updateAlarmList(alarms)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading existing alarms", e)
                Snackbar.make(findViewById(android.R.id.content), "Error loading alarms", Snackbar.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateAlarmList(alarms: List<ClockAlarmEntity>) {
        // Update the alarm list UI if you have one
        // This would show existing alarms for the clock
        Log.d(TAG, "Found ${alarms.size} existing alarms for clock $clockId")
    }
    
    // Cancel alarm functionality
    private fun cancelAlarm(alarmId: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        
        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
        
        lifecycleScope.launch {
            alarmDao.deleteAlarm(alarmId)
            Snackbar.make(findViewById(android.R.id.content), "Alarm cancelled", Snackbar.LENGTH_SHORT).show()
        }
    }
}

// Alarm Receiver for handling alarm triggers
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra("alarm_id", -1)
        val clockId = intent.getIntExtra("clock_id", -1)
        val label = intent.getStringExtra("label") ?: ""
        val soundEnabled = intent.getBooleanExtra("sound_enabled", true)
        val vibrationEnabled = intent.getBooleanExtra("vibration_enabled", true)
        
        Log.d("AlarmReceiver", "Alarm triggered: $alarmId for clock $clockId")
        
        // Show alarm notification/activity
        showAlarmNotification(context, alarmId, clockId, label, soundEnabled, vibrationEnabled)
    }
    
    private fun showAlarmNotification(context: Context, alarmId: Long, clockId: Int, label: String, soundEnabled: Boolean, vibrationEnabled: Boolean) {
        // Create full-screen alarm activity
        val alarmIntent = Intent(context, AlarmRingingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("alarm_id", alarmId)
            putExtra("clock_id", clockId)
            putExtra("label", label)
            putExtra("sound_enabled", soundEnabled)
            putExtra("vibration_enabled", vibrationEnabled)
        }
        context.startActivity(alarmIntent)
    }
}

// Full-screen alarm ringing activity
@AndroidEntryPoint
class AlarmRingingActivity : AppCompatActivity() {
    
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_ringing)
        
        val alarmId = intent.getLongExtra("alarm_id", -1)
        val clockId = intent.getIntExtra("clock_id", -1)
        val label = intent.getStringExtra("label") ?: ""
        val soundEnabled = intent.getBooleanExtra("sound_enabled", true)
        val vibrationEnabled = intent.getBooleanExtra("vibration_enabled", true)
        
        // Set up UI
        findViewById<TextView>(R.id.alarmLabel).text = if (label.isNotEmpty()) label else "Alarm"
        
        // Start sound and vibration
        if (soundEnabled) {
            startAlarmSound()
        }
        
        if (vibrationEnabled) {
            startVibration()
        }
        
        // Set up dismiss button
        findViewById<Button>(R.id.dismissButton).setOnClickListener {
            stopAlarm()
            finish()
        }
    }
    
    private fun startAlarmSound() {
        mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound) // You'll need to add an alarm sound file
        mediaPlayer?.isLooping = true
        mediaPlayer?.start()
    }
    
    private fun startVibration() {
        vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        val pattern = longArrayOf(0, 1000, 500, 1000) // Vibrate pattern
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }
    
    private fun stopAlarm() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        
        vibrator?.cancel()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
    }
} 