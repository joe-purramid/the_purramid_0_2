// DiceColorPickerDialogFragment.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.ui.PurramidPalette // Import the shared palette
import com.example.purramid.thepurramid.data.db.DEFAULT_EMPTY_JSON_MAP
import com.example.purramid.thepurramid.databinding.DialogDiceColorPickerBinding
import com.example.purramid.thepurramid.databinding.IncludeDiceColorRowBinding
import com.example.purramid.thepurramid.randomizers.viewmodel.RandomizerSettingsViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID

@AndroidEntryPoint
class DiceColorPickerDialogFragment : DialogFragment() {

    private var _binding: DialogDiceColorPickerBinding? = null
    private val binding get() = _binding!!

    private val settingsViewModel: RandomizerSettingsViewModel by activityViewModels()
    private val gson = Gson()

    private lateinit var rowBindings: Map<Int, IncludeDiceColorRowBinding>
    private var currentDiceColorConfig: MutableMap<Int, Int> = mutableMapOf()
    private var applyColorToAllChecked = false

    // Use the shared palette
    private val availableColors = PurramidPalette.dicePaletteColors
    private val allDieTypesForApplyAll = listOf(4, 6, 8, 10, DicePoolDialogFragment.D10_TENS_KEY, DicePoolDialogFragment.D10_UNITS_KEY, 12, 20)


    companion object {
        const val TAG = "DiceColorPickerDialog"
        fun newInstance(instanceId: UUID): DiceColorPickerDialogFragment {
            return DiceColorPickerDialogFragment()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogDiceColorPickerBinding.inflate(LayoutInflater.from(context))
        initializeRowBindings()
        setupListeners()
        observeSettings()

        return AlertDialog.Builder(requireActivity())
            .setView(binding.root)
            .create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun initializeRowBindings() {
        rowBindings = mapOf(
            4 to IncludeDiceColorRowBinding.bind(binding.diceColorRowD4.root),
            6 to IncludeDiceColorRowBinding.bind(binding.diceColorRowD6.root),
            8 to IncludeDiceColorRowBinding.bind(binding.diceColorRowD8.root),
            10 to IncludeDiceColorRowBinding.bind(binding.diceColorRowD10.root),
            DicePoolDialogFragment.D10_TENS_KEY to IncludeDiceColorRowBinding.bind(binding.diceColorRowD10Tens.root),
            DicePoolDialogFragment.D10_UNITS_KEY to IncludeDiceColorRowBinding.bind(binding.diceColorRowD10Units.root),
            12 to IncludeDiceColorRowBinding.bind(binding.diceColorRowD12.root),
            20 to IncludeDiceColorRowBinding.bind(binding.diceColorRowD20.root)
        )
    }

    private fun setupListeners() {
        binding.diceColorPickerCloseButton.setOnClickListener { dismiss() }

        binding.checkboxApplyColorToAll.setOnCheckedChangeListener { _, isChecked ->
            applyColorToAllChecked = isChecked
        }

        rowBindings.forEach { (sides, rowBinding) ->
            setRowLabel(sides, rowBinding.diceTypeLabel)
            rowBinding.colorSquareView.setOnClickListener {
                showPaletteSelectionDialog(sides) // Launch our custom palette dialog
            }
        }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.settings.observe(viewLifecycleOwner) { settings ->
                    if (settings != null) {
                        currentDiceColorConfig = parseDiceColorConfig(settings.diceColorConfigJson) ?: mutableMapOf()
                        updateColorSquaresUI()

                        val percentileEnabled = settings.isPercentileDiceEnabled
                        binding.diceColorRowD10Tens.root.visibility = if (percentileEnabled) View.VISIBLE else View.GONE
                        binding.diceColorRowD10Units.root.visibility = if (percentileEnabled) View.VISIBLE else View.GONE
                        binding.diceColorRowD10.root.visibility = if (percentileEnabled) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    private fun updateColorSquaresUI() {
        rowBindings.forEach { (sides, rowBinding) ->
            // Use the actual configured color, or UI_DEFAULT_COLOR_INT if not set (for visibility of the square)
            val color = currentDiceColorConfig[sides] ?: PurramidPalette.UI_DEFAULT_COLOR_INT
            val background = rowBinding.colorSquareView.background
            if (background is GradientDrawable) { // Assuming color_square_background_selector uses a shape that results in GradientDrawable
                background.setColorFilter(color, PorterDuff.Mode.SRC_ATOP) // Or just setColor if it's a solid
            } else { // Fallback for other drawable types
                rowBinding.colorSquareView.background = ColorDrawable(color)
            }
        }
    }

    private fun setRowLabel(sides: Int, textView: TextView) {
        val label = getDieLabel(sides)
        textView.text = label
    }

    private fun getDieLabel(sides: Int): String {
        return when (sides) {
            DicePoolDialogFragment.D10_TENS_KEY -> "d% (00-90)"
            DicePoolDialogFragment.D10_UNITS_KEY -> "d% (0-9)"
            else -> "d$sides"
        }
    }

    /**
     * Shows an AlertDialog with the predefined PurramidPalette colors.
     */
    private fun showPaletteSelectionDialog(dieSidesToUpdate: Int) {
        val colorNames = availableColors.map { it.name }.toTypedArray()
        val currentSelectedColorInt = currentDiceColorConfig[dieSidesToUpdate] ?: PurramidPalette.DEFAULT_DIE_COLOR.colorInt
        val currentlySelectedIndex = availableColors.indexOfFirst { it.colorInt == currentSelectedColorInt }.coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pick_color_for_die, getDieLabel(dieSidesToUpdate)))
            .setSingleChoiceItems(colorNames, currentlySelectedIndex) { dialog, which ->
                val selectedNamedColor = availableColors[which]

                if (applyColorToAllChecked) {
                    val typesToColor = if (settingsViewModel.settings.value?.isPercentileDiceEnabled == true) {
                        allDieTypesForApplyAll
                    } else {
                        // Filter out percentile keys if percentile mode is off
                        allDieTypesForApplyAll.filterNot { it == DicePoolDialogFragment.D10_TENS_KEY || it == DicePoolDialogFragment.D10_UNITS_KEY }
                    }
                    typesToColor.forEach { type ->
                        if (selectedNamedColor.isDefault) {
                            currentDiceColorConfig.remove(type) // Remove to use default tinting behavior
                        } else {
                            currentDiceColorConfig[type] = selectedNamedColor.colorInt
                        }
                    }
                } else {
                    // Apply to the specific die type clicked
                    if (selectedNamedColor.isDefault) {
                        currentDiceColorConfig.remove(dieSidesToUpdate)
                        // If percentile is enabled and a d10 was clicked, also clear the d10_units
                        if (settingsViewModel.settings.value?.isPercentileDiceEnabled == true && dieSidesToUpdate == 10) {
                            currentDiceColorConfig.remove(DicePoolDialogFragment.D10_UNITS_KEY)
                        }
                    } else {
                        currentDiceColorConfig[dieSidesToUpdate] = selectedNamedColor.colorInt
                        // If percentile is enabled and a d10 was clicked, also color the d10_units
                        if (settingsViewModel.settings.value?.isPercentileDiceEnabled == true && dieSidesToUpdate == 10) {
                            currentDiceColorConfig[DicePoolDialogFragment.D10_UNITS_KEY] = selectedNamedColor.colorInt
                        }
                    }
                }
                updateColorSquaresUI()
                saveColorConfig()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveColorConfig() {
        val newConfigJson = gson.toJson(currentDiceColorConfig)
        settingsViewModel.updateDiceColorConfig(newConfigJson)
    }

    private fun parseDiceColorConfig(json: String?): MutableMap<Int, Int>? {
        if (json.isNullOrEmpty() || json == DEFAULT_EMPTY_JSON_MAP) {
            return mutableMapOf()
        }
        return try {
            val mapType = object : TypeToken<MutableMap<Int, Int>>() {}.type
            gson.fromJson(json, mapType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse dice color config JSON: $json", e)
            mutableMapOf()
        }
    }
}
