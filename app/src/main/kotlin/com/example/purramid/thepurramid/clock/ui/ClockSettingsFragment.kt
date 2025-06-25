package com.example.purramid.thepurramid.clock.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.clock.ClockOverlayService
import com.example.purramid.thepurramid.clock.ClockAlarmActivity
import com.example.purramid.thepurramid.clock.ui.TimeZoneGlobeActivity
import com.example.purramid.thepurramid.databinding.FragmentClockSettingsBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import java.time.ZoneId

@AndroidEntryPoint
class ClockSettingsFragment : Fragment() {

    private var _binding: FragmentClockSettingsBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val TAG = "ClockSettingsFragment"
        const val TAG_FRAGMENT = "ClockSettingsFragment"
        private const val ARG_CLOCK_ID = "clock_id"
        const val PREFS_NAME = ClockOverlayService.PREFS_NAME_FOR_ACTIVITY
        const val KEY_ACTIVE_COUNT = ClockOverlayService.KEY_ACTIVE_COUNT_FOR_ACTIVITY

        fun newInstance(clockId: Int): ClockSettingsFragment {
            return ClockSettingsFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_CLOCK_ID, clockId)
                }
            }
        }
    }

    private var currentClockIdForConfig: Int = -1
    private var selectedColor: Int = Color.WHITE
    private var selectedColorView: View? = null
    private lateinit var uiStatePrefs: SharedPreferences
    private lateinit var serviceStatePrefs: SharedPreferences

    private val timeZoneResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val selectedTimeZoneId = result.data?.getStringExtra("selected_time_zone_id")
            selectedTimeZoneId?.let {
                Log.d(TAG, "Time zone selected: $it for clock $currentClockIdForConfig")
                sendUpdateIntentToService("time_zone", it)
                uiStatePrefs.edit().putString("clock_${currentClockIdForConfig}_time_zone_id", it).apply()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentClockSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentClockIdForConfig = arguments?.getInt(ARG_CLOCK_ID, 0) ?: 0
        Log.d(TAG, "Configuring settings for clock ID: $currentClockIdForConfig (0 means general or first)")

        uiStatePrefs = requireContext().getSharedPreferences("clock_settings_ui_prefs", Context.MODE_PRIVATE)
        serviceStatePrefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        loadInitialUiState()
        setupListeners()
        updateAddAnotherButtonState()
    }

    private fun loadInitialUiState() {
        val idToLoad = if (currentClockIdForConfig > 0) currentClockIdForConfig else 0
        val savedMode = uiStatePrefs.getString("clock_${idToLoad}_mode", "digital")
        binding.modeToggleButton.isChecked = (savedMode == "analog")
        setupColorPalette()
        val is24Hour = uiStatePrefs.getBoolean("clock_${idToLoad}_24hour", false)
        binding.twentyFourHourToggleButton.isChecked = is24Hour
        val displaySeconds = uiStatePrefs.getBoolean("clock_${idToLoad}_display_seconds", true)
        binding.secondsToggleButton.isChecked = displaySeconds
        val isNested = uiStatePrefs.getBoolean("clock_${idToLoad}_nest", false)
        binding.nestToggleButton.isChecked = isNested
    }

    private fun setupColorPalette() {
        val colors = resources.getIntArray(R.array.clock_palette_colors)
        val outlineColors = resources.getIntArray(R.array.clock_palette_outline_colors)
        binding.colorPalette.removeAllViews()
        colors.forEachIndexed { index, colorValue ->
            val colorView = View(requireContext()).apply {
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
                    updateColorSelectionInUI(this)
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

    private fun updateColorSelectionInUI(selectedView: View) {
        selectedColorView?.background?.alpha = 255
        selectedView.background?.alpha = 128
        selectedColorView = selectedView
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
            val intent = Intent(requireContext(), TimeZoneGlobeActivity::class.java)
            val currentZoneId = uiStatePrefs.getString("clock_${if(currentClockIdForConfig > 0) currentClockIdForConfig else 0}_time_zone_id", ZoneId.systemDefault().id)
            intent.putExtra("current_time_zone_id", currentZoneId)
            timeZoneResultLauncher.launch(intent)
        }
        binding.secondsToggleButton.setOnCheckedChangeListener { _, isChecked ->
            sendUpdateIntentToService("seconds", isChecked)
            uiStatePrefs.edit().putBoolean("clock_${if(currentClockIdForConfig > 0) currentClockIdForConfig else 0}_display_seconds", isChecked).apply()
        }
        binding.setAlarmButton.setOnClickListener {
            val alarmIntent = Intent(requireContext(), ClockAlarmActivity::class.java).apply {
                putExtra(ClockAlarmActivity.EXTRA_CLOCK_ID, currentClockIdForConfig)
            }
            startActivity(alarmIntent)
        }
        binding.nestToggleButton.setOnCheckedChangeListener { _, isChecked ->
            if (currentClockIdForConfig > 0) {
                val serviceIntent = Intent(requireContext(), ClockOverlayService::class.java).apply {
                    action = ClockOverlayService.ACTION_NEST_CLOCK
                    putExtra(ClockOverlayService.EXTRA_CLOCK_ID, currentClockIdForConfig)
                    putExtra(ClockOverlayService.EXTRA_NEST_STATE, isChecked)
                }
                ContextCompat.startForegroundService(requireContext(), serviceIntent)
                uiStatePrefs.edit().putBoolean("clock_${currentClockIdForConfig}_nest", isChecked).apply()
            } else {
                Snackbar.make(binding.root, "Select a clock to nest.", Snackbar.LENGTH_SHORT).show()
                binding.nestToggleButton.isChecked = !isChecked
            }
        }
        binding.addAnotherClockButton.setOnClickListener {
            val activeCount = serviceStatePrefs.getInt(KEY_ACTIVE_COUNT, 0)
            if (activeCount < 4) { // MAX_CLOCKS from ClockOverlayService
                val serviceIntent = Intent(requireContext(), ClockOverlayService::class.java).apply {
                    action = ClockOverlayService.ACTION_ADD_NEW_CLOCK
                }
                ContextCompat.startForegroundService(requireContext(), serviceIntent)
                Snackbar.make(binding.root, "New clock added!", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Maximum number of clocks (4) reached.", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendUpdateIntentToService(key: String, value: Any) {
        val serviceIntent = Intent(requireContext(), ClockOverlayService::class.java).apply {
            action = ClockOverlayService.ACTION_UPDATE_CLOCK_SETTING
            putExtra(ClockOverlayService.EXTRA_CLOCK_ID, currentClockIdForConfig)
            putExtra(ClockOverlayService.EXTRA_SETTING_TYPE, key)
            when (value) {
                is String -> putExtra(ClockOverlayService.EXTRA_SETTING_VALUE, value)
                is Boolean -> putExtra(ClockOverlayService.EXTRA_SETTING_VALUE, value)
                is Int -> putExtra(ClockOverlayService.EXTRA_SETTING_VALUE, value)
                else -> Log.w(TAG, "Unsupported value type for setting: $key")
            }
        }
        ContextCompat.startForegroundService(requireContext(), serviceIntent)
        Log.d(TAG, "Sent update to service: $key = $value for clock $currentClockIdForConfig")
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun updateAddAnotherButtonState() {
        val activeCount = serviceStatePrefs.getInt(KEY_ACTIVE_COUNT, 0)
        binding.addAnotherClockButton.isEnabled = activeCount < 4 // MAX_CLOCKS from ClockOverlayService
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 