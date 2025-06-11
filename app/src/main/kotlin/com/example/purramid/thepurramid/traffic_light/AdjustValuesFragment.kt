// AdjustValuesFragment.kt
package com.example.purramid.thepurramid.traffic_light

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog // For info dialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels // If sharing with Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.FragmentAdjustValuesBinding
import com.example.purramid.thepurramid.databinding.ItemDbRangeEditorBinding // For included layouts
import com.example.purramid.thepurramid.traffic_light.viewmodel.DbRange
import com.example.purramid.thepurramid.traffic_light.viewmodel.ResponsiveModeSettings
import com.example.purramid.thepurramid.traffic_light.viewmodel.TrafficLightViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class AdjustValuesFragment : DialogFragment() {

    private var _binding: FragmentAdjustValuesBinding? = null
    private val binding get() = _binding!!

    // Bindings for included layouts
    private lateinit var greenRangeBinding: ItemDbRangeEditorBinding
    private lateinit var yellowRangeBinding: ItemDbRangeEditorBinding
    private lateinit var redRangeBinding: ItemDbRangeEditorBinding

    private val viewModel: TrafficLightViewModel by activityViewModels()
    private var blockListeners: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdjustValuesBinding.inflate(inflater, container, false)
        // Inflate/bind included layouts
        greenRangeBinding = ItemDbRangeEditorBinding.bind(binding.includeGreenRange.root)
        yellowRangeBinding = ItemDbRangeEditorBinding.bind(binding.includeYellowRange.root)
        redRangeBinding = ItemDbRangeEditorBinding.bind(binding.includeRedRange.root)
        return binding.root
    }

    // For a full-screen dialog or more control, you might override onCreateDialog
    // For now, letting DialogFragment manage it as a standard dialog.

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.setTitle(R.string.setting_adjust_values) // Set title if not using MaterialAlertDialogBuilder in onCreateDialog

        setupViews()
        observeViewModel()
    }

    private fun setupViews() {
        // Set color indicators (these could be simple colored drawables)
        greenRangeBinding.imageColorIndicator.setImageResource(R.drawable.ic_circle_green_filled) // Create these drawables
        yellowRangeBinding.imageColorIndicator.setImageResource(R.drawable.ic_circle_yellow_filled)
        redRangeBinding.imageColorIndicator.setImageResource(R.drawable.ic_circle_red_filled)

        // Setup TextWatchers for EditTexts (more complex logic needed here)
        setupEditTextListener(greenRangeBinding.editTextMinDb, ColorForRange.GREEN, true)
        setupEditTextListener(greenRangeBinding.editTextMaxDb, ColorForRange.GREEN, false)
        setupEditTextListener(yellowRangeBinding.editTextMinDb, ColorForRange.YELLOW, true)
        setupEditTextListener(yellowRangeBinding.editTextMaxDb, ColorForRange.YELLOW, false)
        setupEditTextListener(redRangeBinding.editTextMinDb, ColorForRange.RED, true)
        setupEditTextListener(redRangeBinding.editTextMaxDb, ColorForRange.RED, false)


        binding.checkboxDangerousSoundAlert.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setDangerousSoundAlert(isChecked)
        }

        binding.buttonDangerousSoundInfo.setOnClickListener {
            showDangerousSoundInfoDialog()
        }

        binding.buttonSaveAdjustments.setOnClickListener {
            // Logic to commit changes if not already live, then dismiss
            // For now, changes are live via ViewModel.
            dismiss()
        }
        binding.buttonCancelAdjustments.setOnClickListener {
            // TODO: Optionally revert to original values if changes aren't live.
            // For now, just dismiss. ViewModel holds the state.
            dismiss()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    blockListeners = true
                    updateDbRangeUI(greenRangeBinding, state.responsiveModeSettings.greenRange)
                    updateDbRangeUI(yellowRangeBinding, state.responsiveModeSettings.yellowRange)
                    updateDbRangeUI(redRangeBinding, state.responsiveModeSettings.redRange)
                    binding.checkboxDangerousSoundAlert.isChecked = state.responsiveModeSettings.dangerousSoundAlertEnabled
                    blockListeners = false
                }
            }
        }
    }

    private fun updateDbRangeUI(rangeBinding: ItemDbRangeEditorBinding, dbRange: DbRange) {
        if (dbRange.isNa()) {
            rangeBinding.editTextMinDb.setText(getString(R.string.na_value))
            rangeBinding.editTextMaxDb.setText(getString(R.string.na_value))
            rangeBinding.editTextMinDb.isEnabled = false
            rangeBinding.editTextMaxDb.isEnabled = false
        } else {
            rangeBinding.editTextMinDb.setText(dbRange.minDb?.toString() ?: "")
            rangeBinding.editTextMaxDb.setText(dbRange.maxDb?.toString() ?: "")
            rangeBinding.editTextMinDb.isEnabled = true
            rangeBinding.editTextMaxDb.isEnabled = true
        }
    }

    private enum class ColorForRange { GREEN, YELLOW, RED }

    private fun setupEditTextListener(editText: EditText, color: ColorForRange, isMin: Boolean) {
        editText.doAfterTextChanged { text ->
            if (blockListeners) return@doAfterTextChanged
            if (text.toString() == getString(R.string.na_value)) return@doAfterTextChanged

            val value = text.toString().toIntOrNull()
            // Call ViewModel to update, e.g., viewModel.updateDbValue(color, isMin, value)
            // The ViewModel will handle linked logic and update the state, which then flows back to UI.
            // For now, this is a placeholder for the more complex update logic.
             viewModel.updateSpecificDbValue(
                 colorForRange = color,
                 isMinField = isMin,
                 newValue = value
             )
        }
    }


    private fun showDangerousSoundInfoDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dangerous_sound_alert_info_title)
            .setMessage(R.string.dangerous_sound_alert_info_message) // Purramid brand name will be in this string
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AdjustValuesDialog"
        fun newInstance(): AdjustValuesFragment {
            return AdjustValuesFragment()
        }
    }
}