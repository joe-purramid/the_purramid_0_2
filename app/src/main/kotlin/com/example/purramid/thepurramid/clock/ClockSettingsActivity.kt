// ClockSettingsActivity.kt
package com.example.purramid.thepurramid.clock

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast // Keep for alarm app fallback
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
// Make sure binding is generated and imported
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.clock.viewmodel.ClockViewModel // For EXTRA_CLOCK_ID key
import com.example.purramid.thepurramid.databinding.ActivityClockSettingsBinding // Import binding
import com.example.purramid.thepurramid.clock.ui.TimeZoneGlobeActivity // Ensure this import is correct
import com.example.purramid.thepurramid.ui.PurramidPalette
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import java.time.ZoneId

@AndroidEntryPoint
class ClockSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClockSettingsBinding // ViewBinding

    companion object {
        private const val TAG = "ClockSettingsActivity"
        // Use the key defined in ClockViewModel/ClockOverlayService for consistency
        const val EXTRA_CLOCK_ID_CONFIG = ClockOverlayService.EXTRA_CLOCK_ID
        // PREFS_NAME and KEY_ACTIVE_COUNT from ClockOverlayService
        const val PREFS_NAME = ClockOverlayService.PREFS_NAME_FOR_ACTIVITY
        const val KEY_ACTIVE_COUNT = ClockOverlayService.KEY_ACTIVE_COUNT_FOR_ACTIVITY
    }

    private var currentClockIdForConfig: Int = -1 // ID of the clock whose settings are being configured
    private var selectedColor: Int = Color.WHITE
    private var selectedColorView: View? = null

    // SharedPreferences for reading UI state hints (as before) and active clock count
    private lateinit var uiStatePrefs: SharedPreferences
    private lateinit var serviceStatePrefs: SharedPreferences


    private val timeZoneResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedTimeZoneId = result.data?.getStringExtra("selected_time_zone_id")
            selectedTimeZoneId?.let {
                Log.d(TAG, "Time zone selected: $it for clock $currentClockIdForConfig")
                sendUpdateIntentToService("time_zone", it)
                uiStatePrefs.edit().putString("clock_${currentClockIdForConfig}_time_zone_id", it).apply()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClockSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentClockIdForConfig = intent.getIntExtra(EXTRA_CLOCK_ID_CONFIG, 0) // Default to 0 if no specific ID
        Log.d(TAG, "Configuring settings for clock ID: $currentClockIdForConfig (0 means general or first)")

        uiStatePrefs = getSharedPreferences("clock_settings_ui_prefs", Context.MODE_PRIVATE)
        serviceStatePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)


        // If currentClockIdForConfig is 0, it means this settings activity might be for global defaults
        // or for the next clock to be added. If it's > 0, it's for a specific existing clock.
        // The sendUpdateIntentToService needs to handle this (send ID if > 0).

        loadInitialUiState()
        setupListeners()
        updateAddAnotherButtonState()
    }

    private fun loadInitialUiState() {
        // If currentClockIdForConfig is 0, load general defaults.
        // If > 0, load settings for that specific clock.
        // This example assumes settings are per-clock, adjust if you have global defaults.
        val idToLoad = if (currentClockIdForConfig > 0) currentClockIdForConfig else 0 // Placeholder for "default" concept

        val savedMode = uiStatePrefs.getString("clock_${idToLoad}_mode", "digital")
        binding.modeToggleButton.isChecked = (savedMode == "analog")

        setupColorPalette()

        val is24Hour = uiStatePrefs.getBoolean("clock_${idToLoad}_24hour", false)
        binding.twentyFourHourToggleButton.isChecked = is24Hour

        val displaySeconds = uiStatePrefs.getBoolean("clock_${idToLoad}_display_seconds", true)
        binding.secondsToggleButton.isChecked = displaySeconds

        val isNested = uiStatePrefs.getBoolean("clock_${idToLoad}_nest", false)
        binding.nestToggleButton.isChecked = isNested

        // Enable overlay button is likely not per-instance, but a global app action or permission check
        binding.enableOverlayButton.setOnClickListener {
            // This button might be better placed in MainActivity or an "App Settings" screen
            // For now, it can just send a generic start action if no clocks are running
            val activeCount = serviceStatePrefs.getInt(KEY_ACTIVE_COUNT, 0)
            if (activeCount == 0) {
                val serviceIntent = Intent(this, ClockOverlayService::class.java).apply {
                    action = ACTION_ADD_NEW_CLOCK // Start by adding one if none exist
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                Snackbar.make(binding.root, "Clocks are already enabled.", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupColorPalette() {
        binding.colorPalette.removeAllViews()
        colors.forEachIndexed { index, colorValue ->
            val colorView = View(this).apply {
                val sizeInDp = 40
                val sizeInPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sizeInDp.toFloat(), resources.displayMetrics).toInt()
                layoutParams = LinearLayout.LayoutParams(sizeInPx, sizeInPx).apply {
                    setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorValue)
                    setStroke(dpToPx(1), outlineColors[index])
                }
                setOnClickListener {
                    selectedColor = colorValue
                    updateColorSelectionInUI(this) // Renamed for clarity
                    sendUpdateIntentToService("color", colorValue)
                    uiStatePrefs.edit().putInt("clock_${if(currentClockIdForConfig > 0) currentClockIdForConfig else 0}_color", colorValue).apply()
                }
            }
            binding.colorPalette.addView(colorView)
            if (colorValue == selectedColor) {
                updateColorSelectionInUI(colorView)
            }
        }
    }

    private fun setupListeners() {
        binding.modeToggleButton.setOnCheckedChangeListener { _, isChecked ->
            val newMode = if (isChecked) "analog" else "digital"
            sendUpdateIntentToService("mode", newMode)
            uiStatePrefs.edit().putString("clock_${if(currentClockIdForConfig > 0) currentClockIdForConfig else 0}_mode", newMode).apply()
        }

        binding.twentyFourHourToggleButton.setOnCheckedChangeListener { _, isChecked ->
            sendUpdateIntentToService("24hour", isChecked)
            uiStatePrefs.edit().putBoolean("clock_${if(currentClockIdForConfig > 0) currentClockIdForConfig else 0}_24hour", isChecked).apply()
        }

        binding.setTimeZoneButton.setOnClickListener {
            val intent = Intent(this, TimeZoneGlobeActivity::class.java)
            // Pass current zone ID for initial selection in globe activity
            val currentZoneId = uiStatePrefs.getString("clock_${if(currentClockIdForConfig > 0) currentClockIdForConfig else 0}_time_zone_id", ZoneId.systemDefault().id)
            intent.putExtra("current_time_zone_id", currentZoneId)
            timeZoneResultLauncher.launch(intent)
        }

        binding.secondsToggleButton.setOnCheckedChangeListener { _, isChecked ->
            sendUpdateIntentToService("seconds", isChecked)
            uiStatePrefs.edit().putBoolean("clock_${if(currentClockIdForConfig > 0) currentClockIdForConfig else 0}_display_seconds", isChecked).apply()
        }

        binding.setAlarmButton.setOnClickListener {
            try { startActivity(Intent(AlarmClock.ACTION_SET_ALARM)) }
            catch (e: Exception) {
                Toast.makeText(this, "Could not open alarm app", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Error starting alarm intent", e)
            }
        }

        binding.nestToggleButton.setOnCheckedChangeListener { _, isChecked ->
            if (currentClockIdForConfig > 0) { // Nesting applies to a specific clock
                val serviceIntent = Intent(this, ClockOverlayService::class.java).apply {
                    action = ACTION_NEST_CLOCK
                    putExtra(EXTRA_CLOCK_ID, currentClockIdForConfig)
                    putExtra(EXTRA_NEST_STATE, isChecked)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                uiStatePrefs.edit().putBoolean("clock_${currentClockIdForConfig}_nest", isChecked).apply()
            } else {
                Snackbar.make(binding.root, "Select a clock to nest.", Snackbar.LENGTH_SHORT).show()
                binding.nestToggleButton.isChecked = !isChecked // Revert toggle
            }
        }

        binding.addAnotherClockButton.setOnClickListener {
            val activeCount = serviceStatePrefs.getInt(KEY_ACTIVE_COUNT, 0)
            if (activeCount < ClockOverlayService.MAX_CLOCKS) {
                val serviceIntent = Intent(this, ClockOverlayService::class.java).apply {
                    action = ACTION_ADD_NEW_CLOCK // Service generates new ID
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                // Don't finish; allow adding more or changing other settings
            } else {
                Snackbar.make(binding.root, "Maximum of ${ClockOverlayService.MAX_CLOCKS} clocks reached.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun sendUpdateIntentToService(settingType: String, value: Any) {
        // If currentClockIdForConfig is 0, this update might apply to a "default" setting
        // or the service needs to know how to handle it (e.g., apply to all, or ignore).
        // For now, assume if ID is 0, it's a general setting for new clocks,
        // but the service side isn't set up for global default changes via this mechanism yet.
        // Best if settings always target a specific ID, or global defaults are handled differently.
        // Let's only send if we have a specific clock ID.
        if (currentClockIdForConfig <= 0) {
            Log.w(TAG, "Attempting to send setting update without specific clock ID. Type: $settingType. Ignoring for now.")
            // Or, you could store these as "next clock defaults" in SharedPreferences.
            return
        }

        val serviceIntent = Intent(this, ClockOverlayService::class.java).apply {
            action = ACTION_UPDATE_CLOCK_SETTING
            putExtra(EXTRA_CLOCK_ID, currentClockIdForConfig) // Target specific clock
            putExtra(EXTRA_SETTING_TYPE, settingType)
            when (value) {
                is String -> putExtra(EXTRA_SETTING_VALUE, value)
                is Int -> putExtra(EXTRA_SETTING_VALUE, value)
                is Boolean -> putExtra(EXTRA_SETTING_VALUE, value)
                else -> { Log.w(TAG, "Unsupported value type for setting '$settingType'"); return }
            }
        }
        Log.d(TAG, "Sending update to service: clockId=$currentClockIdForConfig, type=$settingType, value=$value")
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    // Ensure updateColorSelectionInUI correctly highlights the selected view
    // It might need to iterate through children of binding.colorPalette to reset others
    private fun updateColorSelectionInUI(newSelection: View) {
        selectedColorView?.background?.apply {
            if (this is GradientDrawable) {
                val originalColor = selectedColorView?.tag as? Int ?: PurramidPalette.WHITE.colorInt
                val originalOutline = if (Color.luminance(originalColor) > 0.5) Color.BLACK else Color.WHITE
                setStroke(dpToPx(1), originalOutline)
            }
        }
        // Highlight new selection
        newSelection.background?.apply {
            if (this is GradientDrawable) {
                setStroke(dpToPx(3), Color.CYAN)
            }
        }
        selectedColorView = newSelection
    }

    private fun updateAddAnotherButtonState() {
        val numberOfClocks = serviceStatePrefs.getInt(KEY_ACTIVE_COUNT, 0)
        binding.addAnotherClockButton.isEnabled = numberOfClocks < ClockOverlayService.MAX_CLOCKS
        binding.addAnotherClockButton.alpha = if (binding.addAnotherClockButton.isEnabled) 1.0f else 0.5f
    }

    override fun onResume() {
        super.onResume()
        updateAddAnotherButtonState() // Refresh count in case service changed it
        // Potentially reload UI state for currentClockIdForConfig if it can change externally
    }

}