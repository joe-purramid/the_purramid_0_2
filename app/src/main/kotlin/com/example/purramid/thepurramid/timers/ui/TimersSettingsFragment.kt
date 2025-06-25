// TimersSettingsFragment.kt
package com.example.purramid.thepurramid.timers.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.FragmentTimersSettingsBinding
import com.example.purramid.thepurramid.instance.InstanceManager
import com.example.purramid.thepurramid.timers.TimerType
import com.example.purramid.thepurramid.timers.TimersActivity
import com.example.purramid.thepurramid.timers.TimersService
import com.example.purramid.thepurramid.timers.viewmodel.TimersViewModel
import com.example.purramid.thepurramid.ui.PurramidPalette
import com.example.purramid.thepurramid.util.dpToPx
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class TimersSettingsFragment : DialogFragment() {

    @Inject lateinit var instanceManager: InstanceManager

    private var _binding: FragmentTimersSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TimersViewModel by activityViewModels()

    private var blockListeners = false
    private var selectedTimerColor: Int = PurramidPalette.WHITE.colorInt
    private var selectedTimerColorView: View? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimersSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.setTitle(R.string.timer_settings_title)

        setupTimerColorPalette()
        setupListeners()
        observeViewModel()
    }

    private fun setupTimerColorPalette() {
        binding.timerColorPalette.removeAllViews()
        val marginInPx = requireContext().dpToPx(8)

        PurramidPalette.appStandardPalette.forEach { namedColor ->
            val colorValue = namedColor.colorInt
            val colorView = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    requireContext().dpToPx(40),
                    requireContext().dpToPx(40)
                ).apply {
                    setMargins(marginInPx, 0, marginInPx, 0)
                }

                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = requireContext().dpToPx(4).toFloat()
                    setColor(colorValue)
                    val strokeColor = if (Color.luminance(colorValue) > 0.5) Color.BLACK else Color.WHITE
                    setStroke(requireContext().dpToPx(1), strokeColor)
                }
                background = drawable
                tag = colorValue

                setOnClickListener {
                    selectedTimerColor = colorValue
                    updateTimerColorSelectionInUI(colorValue)
                    viewModel.updateOverlayColor(colorValue)
                }
            }
            binding.timerColorPalette.addView(colorView)
        }
    }

    private fun updateTimerColorSelectionInUI(activeColor: Int) {
        for (i in 0 until binding.timerColorPalette.childCount) {
            val childView = binding.timerColorPalette.getChildAt(i)
            val viewColor = childView.tag as? Int ?: continue
            val drawable = childView.background as? GradientDrawable

            if (viewColor == activeColor) {
                drawable?.setStroke(requireContext().dpToPx(3), Color.CYAN)
                selectedTimerColorView = childView
            } else {
                val outline = if (Color.luminance(viewColor) > 0.5) Color.BLACK else Color.WHITE
                drawable?.setStroke(requireContext().dpToPx(1), outline)
            }
        }
    }

    private fun setupListeners() {
        binding.buttonCloseSettings.setOnClickListener {
            if (viewModel.uiState.value.type == TimerType.COUNTDOWN) {
                saveDurationFromInput()
            }
            dismiss()
        }

        binding.layoutAddAnother.setOnClickListener {
            handleAddAnotherTimer()
        }

        binding.radioGroupTimerType.setOnCheckedChangeListener { _, checkedId ->
            if (blockListeners) return@setOnCheckedChangeListener
            val newType = when (checkedId) {
                R.id.radioStopwatch -> TimerType.STOPWATCH
                R.id.radioCountdown -> TimerType.COUNTDOWN
                else -> viewModel.uiState.value.type
            }
            if (newType == TimerType.STOPWATCH && viewModel.uiState.value.type == TimerType.COUNTDOWN) {
                saveDurationFromInput()
            }
            viewModel.setTimerType(newType)
        }

        // Duration input listeners
        val durationTextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (blockListeners || viewModel.uiState.value.type != TimerType.COUNTDOWN) return
            }
        }
        binding.editTextHours.addTextChangedListener(durationTextWatcher)
        binding.editTextMinutes.addTextChangedListener(durationTextWatcher)
        binding.editTextSeconds.addTextChangedListener(durationTextWatcher)

        val durationFocusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !blockListeners && viewModel.uiState.value.type == TimerType.COUNTDOWN) {
                saveDurationFromInput()
            }
        }
        binding.editTextHours.onFocusChangeListener = durationFocusListener
        binding.editTextMinutes.onFocusChangeListener = durationFocusListener
        binding.editTextSeconds.onFocusChangeListener = durationFocusListener

        binding.switchPlaySoundOnEnd.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setPlaySoundOnEnd(isChecked)
        }

        binding.switchShowCentiseconds.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setShowCentiseconds(isChecked)
        }

        // Stopwatch specific
        binding.switchLapTime.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setShowLapTimes(isChecked)
        }

        binding.switchSounds.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setSoundsEnabled(isChecked)
        }

        // Countdown specific
        binding.switchNestTimer.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setNested(isChecked)
        }

        // Set Countdown duration
        binding.layoutSetCountdown.setOnClickListener {
            showSetCountdownDialog()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    Log.d(TAG, "Observed state: $state")
                    blockListeners = true

                    // Update Timer Type RadioGroup
                    val radioIdToCheck = when (state.type) {
                        TimerType.STOPWATCH -> R.id.radioStopwatch
                        TimerType.COUNTDOWN -> R.id.radioCountdown
                    }
                    if (binding.radioGroupTimerType.checkedRadioButtonId != radioIdToCheck) {
                        binding.radioGroupTimerType.check(radioIdToCheck)
                    }

                    // Update visibility based on timer type
                    binding.layoutCountdownSettings.isVisible = state.type == TimerType.COUNTDOWN
                    binding.layoutStopwatchSettings.isVisible = state.type == TimerType.STOPWATCH
                    binding.switchNestTimer.isVisible = state.type == TimerType.COUNTDOWN

                    // Update Countdown specific UI
                    if (state.type == TimerType.COUNTDOWN) {
                        // Update duration display
                        val durationStr = formatDuration(state.initialDurationMillis)
                        binding.textViewCurrentDuration.text = durationStr

                        binding.switchPlaySoundOnEnd.isChecked = state.playSoundOnEnd
                        binding.switchNestTimer.isChecked = state.isNested
                    }

                    // Update Stopwatch specific UI
                    if (state.type == TimerType.STOPWATCH) {
                        binding.switchLapTime.isChecked = state.showLapTimes
                        binding.switchSounds.isChecked = state.soundsEnabled
                    }

                    // Update Common Settings
                    binding.switchShowCentiseconds.isChecked = state.showCentiseconds

                    // Update color palette selection
                    selectedTimerColor = state.overlayColor
                    updateTimerColorSelectionInUI(selectedTimerColor)

                    // Update Add Another button state
                    val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.TIMERS)
                    binding.layoutAddAnother.isEnabled = activeCount < 4
                    binding.iconAddAnother.alpha = if (activeCount < 4) 1.0f else 0.5f

                    blockListeners = false
                }
            }
        }
    }

    private fun populateDurationFields(totalMillis: Long) {
        val hours = TimeUnit.MILLISECONDS.toHours(totalMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(totalMillis) % 60

        if (binding.editTextHours.text.toString() != hours.toString()) {
            binding.editTextHours.setText(hours.toString())
        }
        if (binding.editTextMinutes.text.toString() != minutes.toString()) {
            binding.editTextMinutes.setText(minutes.toString())
        }
        if (binding.editTextSeconds.text.toString() != seconds.toString()) {
            binding.editTextSeconds.setText(seconds.toString())
        }
    }

    private fun saveDurationFromInput() {
        val hours = binding.editTextHours.text.toString().toLongOrNull() ?: 0L
        val minutes = binding.editTextMinutes.text.toString().toLongOrNull() ?: 0L
        val seconds = binding.editTextSeconds.text.toString().toLongOrNull() ?: 0L

        if (hours < 0 || minutes < 0 || seconds < 0 || minutes >= 60 || seconds >= 60) {
            Log.w(TAG, "Invalid duration input.")
            Toast.makeText(requireContext(), getString(R.string.invalid_duration), Toast.LENGTH_SHORT).show()
            populateDurationFields(viewModel.uiState.value.initialDurationMillis)
            return
        }

        // Check max time limit (99:59:59)
        if (hours > 99) {
            Toast.makeText(requireContext(), getString(R.string.max_duration_exceeded), Toast.LENGTH_SHORT).show()
            binding.editTextHours.setText("99")
            return
        }

        val totalMillis = TimeUnit.HOURS.toMillis(hours) +
                TimeUnit.MINUTES.toMillis(minutes) +
                TimeUnit.SECONDS.toMillis(seconds)
        viewModel.setInitialDuration(totalMillis)
        Log.d(TAG, "Saved duration: $totalMillis ms")
    }

    private fun handleAddAnotherTimer() {
        val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.TIMERS)
        if (activeCount >= 4) {
            Toast.makeText(
                requireContext(),
                getString(R.string.max_timers_reached),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Save current settings
        if (viewModel.uiState.value.type == TimerType.COUNTDOWN) {
            saveDurationFromInput()
        }

        // Launch new timer with current settings
        val currentState = viewModel.uiState.value
        val intent = Intent(requireContext(), TimersService::class.java).apply {
            action = if (currentState.type == TimerType.COUNTDOWN) {
                com.example.purramid.thepurramid.timers.ACTION_START_COUNTDOWN
            } else {
                com.example.purramid.thepurramid.timers.ACTION_START_STOPWATCH
            }
            if (currentState.type == TimerType.COUNTDOWN) {
                putExtra(com.example.purramid.thepurramid.timers.EXTRA_DURATION_MS, currentState.initialDurationMillis)
            }
        }
        ContextCompat.startForegroundService(requireContext(), intent)

        // Dismiss settings
        dismiss()
    }

    private fun showSetCountdownDialog() {
        val currentDuration = viewModel.uiState.value.initialDurationMillis
        SetCountdownDialog.newInstance(currentDuration) { newDuration ->
            viewModel.setInitialDuration(newDuration)
        }.show(childFragmentManager, "SetCountdownDialog")
    }

    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "TimersSettingsFragment"

        fun newInstance(timerId: Int): TimersSettingsFragment {
            val fragment = TimersSettingsFragment()
            val args = Bundle()
            args.putInt(TimersViewModel.KEY_TIMER_ID, timerId)
            fragment.arguments = args
            return fragment
        }
    }
}