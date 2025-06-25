// TrafficLightSettingsFragment.kt
package com.example.purramid.thepurramid.traffic_light

import android.content.Context // Added for SharedPreferences
import android.content.DialogInterface
import android.content.Intent // Added for Service Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat // Added for starting service
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.FragmentTrafficLightSettingsBinding
import com.example.purramid.thepurramid.traffic_light.viewmodel.Orientation
import com.example.purramid.thepurramid.traffic_light.viewmodel.TrafficLightMode
import com.example.purramid.thepurramid.traffic_light.viewmodel.TrafficLightState
import com.example.purramid.thepurramid.traffic_light.viewmodel.TrafficLightViewModel
import com.google.android.material.snackbar.Snackbar // Added for Snackbar
import kotlinx.coroutines.launch

class TrafficLightSettingsFragment : DialogFragment() { // Or AppCompatDialogFragment for Material theming

    private var _binding: FragmentTrafficLightSettingsBinding? = null
    private val binding get() = _binding!!

    // The ViewModel should be scoped to the specific instance if this fragment edits one,
    // or to a general settings VM if these are global defaults.
    // Assuming TrafficLightActivity provides the correct VM instance via activityViewModels()
    private val viewModel: TrafficLightViewModel by activityViewModels()

    private var blockListeners: Boolean = false

    // Companion object to provide a newInstance method, potentially with instanceId argument
    companion object {
        const val TAG = "TrafficLightSettingsDialog"
        fun newInstance(instanceId: Int = 0): TrafficLightSettingsFragment { // instanceId = 0 for general/new
            val fragment = TrafficLightSettingsFragment()
            // Pass instanceId if settings are for a specific traffic light
            // arguments = Bundle().apply { putInt(TrafficLightViewModel.KEY_INSTANCE_ID, instanceId) }
            return fragment
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrafficLightSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Get instanceId from arguments if passed, to potentially scope ViewModel observation
        // val instanceIdArg = arguments?.getInt(TrafficLightViewModel.KEY_INSTANCE_ID, 0) ?: 0
        // Log.d(TAG, "Settings Fragment for instance ID (from arg): $instanceIdArg")
        // If instanceIdArg is > 0, you'd typically get a specific VM instance.
        // For now, activityViewModels() is used, assuming one primary VM or it handles context.

        setupViews()
        observeViewModelState()
    }

    private fun setupViews() {
        binding.radioGroupMode.setOnCheckedChangeListener { _, checkedId ->
            if (blockListeners) return@setOnCheckedChangeListener
            val newMode = when (checkedId) {
                R.id.radio_manual -> TrafficLightMode.MANUAL_CHANGE
                R.id.radio_responsive -> TrafficLightMode.RESPONSIVE_CHANGE
                R.id.radio_timed -> TrafficLightMode.TIMED_CHANGE
                else -> viewModel.uiState.value.currentMode
            }
            viewModel.setMode(newMode)
        }

        binding.switchOrientation.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setOrientation(if (isChecked) Orientation.HORIZONTAL else Orientation.VERTICAL)
        }

        binding.switchBlinking.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.toggleBlinking(isChecked)
        }

        binding.buttonAdjustValues.setOnClickListener {
            // Ensure instanceId is correctly propagated or handled by AdjustValuesFragment
            AdjustValuesFragment.newInstance().show(
                parentFragmentManager, AdjustValuesFragment.TAG
            )
        }

        binding.buttonAddMessages.setOnClickListener {
            Snackbar.make(binding.root, "Add Messages: Coming Soon", Snackbar.LENGTH_SHORT).show()
        }

        binding.buttonEditSequence.setOnClickListener {
            Snackbar.make(binding.root, "Edit Sequence: Coming Soon", Snackbar.LENGTH_SHORT).show()
        }

        binding.switchShowTimeRemaining.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setShowTimeRemaining(isChecked)
        }

        binding.switchShowTimeline.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setShowTimeline(isChecked)
        }

        binding.buttonAddAnother.setOnClickListener {
            val prefs = requireActivity().getSharedPreferences(TrafficLightActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val activeCount = prefs.getInt(TrafficLightActivity.KEY_ACTIVE_COUNT, 0)

            if (activeCount < TrafficLightService.MAX_TRAFFIC_LIGHTS) {
                Log.d(TAG, "Add new traffic light requested from settings.")
                val serviceIntent = Intent(requireContext(), TrafficLightService::class.java).apply {
                    action = ACTION_ADD_NEW_TRAFFIC_LIGHT_INSTANCE
                }
                ContextCompat.startForegroundService(requireContext(), serviceIntent)
            } else {
                Snackbar.make(binding.root, getString(R.string.max_traffic_lights_reached_snackbar), Snackbar.LENGTH_LONG).show() // Add string
            }
        }
    }

    private fun observeViewModelState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe the single ViewModel instance provided by activityViewModels()
                // This implies that settings here might affect the "primary" or "last focused" instance
                // or global defaults, depending on how the VM is scoped and its ID is set.
                viewModel.uiState.collect { state ->
                    updateUiControls(state)
                }
            }
        }
    }

    private fun updateUiControls(state: TrafficLightState) {
        blockListeners = true

        binding.radioGroupMode.check(
            when (state.currentMode) {
                TrafficLightMode.MANUAL_CHANGE -> R.id.radio_manual
                TrafficLightMode.RESPONSIVE_CHANGE -> R.id.radio_responsive
                TrafficLightMode.TIMED_CHANGE -> R.id.radio_timed
            }
        )

        binding.switchOrientation.isChecked = state.orientation == Orientation.HORIZONTAL
        binding.switchBlinking.isChecked = state.isBlinkingEnabled

        val isResponsive = state.currentMode == TrafficLightMode.RESPONSIVE_CHANGE
        val isTimed = state.currentMode == TrafficLightMode.TIMED_CHANGE
        val isManualOrResponsive = state.currentMode == TrafficLightMode.MANUAL_CHANGE || isResponsive

        binding.buttonAdjustValues.isVisible = isResponsive
        binding.buttonAddMessages.isVisible = isManualOrResponsive
        binding.buttonEditSequence.isVisible = isTimed
        binding.switchShowTimeRemaining.isVisible = isTimed
        binding.switchShowTimeline.isVisible = isTimed

        // Update enabled state of responsive radio button based on mic availability
        binding.radioResponsive.isEnabled = state.isMicrophoneAvailable
        if (!state.isMicrophoneAvailable && isResponsive) {
            // If responsive is selected but mic becomes unavailable, revert to manual.
            // This state correction should ideally be in the ViewModel.
            viewModel.setMode(TrafficLightMode.MANUAL_CHANGE) // This will trigger another state emission
        }

        // Update "Add Another" button enabled state based on actual count from prefs
        // (Though ideally, ViewModel would expose this count if it's central to its operation)
        val prefs = context?.getSharedPreferences(TrafficLightActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val activeCount = prefs?.getInt(TrafficLightActivity.KEY_ACTIVE_COUNT, 0) ?: 0
        binding.buttonAddAnother.isEnabled = activeCount < TrafficLightService.MAX_TRAFFIC_LIGHTS

        blockListeners = false
    }


    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // No longer directly setting viewModel.setSettingsOpen(false)
        // The Activity closing or fragment being removed handles this implicitly
        // if the settings were a full-screen fragment.
        // If it's a dialog fragment, the hosting activity would know it's dismissed.
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}