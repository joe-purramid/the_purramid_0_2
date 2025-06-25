// DicePoolDialogFragment.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels // Use activityViewModels to share with HostActivity/Parent Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.DEFAULT_DICE_POOL_JSON
import com.example.purramid.thepurramid.databinding.DialogDicePoolBinding
import com.example.purramid.thepurramid.databinding.IncludeDicePoolRowBinding // Import binding for the included layout
import com.example.purramid.thepurramid.randomizers.viewmodel.RandomizerSettingsViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID

@AndroidEntryPoint
class DicePoolDialogFragment : DialogFragment() {

    private var _binding: DialogDicePoolBinding? = null
    private val binding get() = _binding!!

    // Share the ViewModel with the hosting Activity/Fragment
    // This assumes RandomizerSettingsViewModel is appropriate for managing settings updates here.
    // Alternatively, inject a specific DicePoolViewModel if more complex dialog logic is needed.
    private val settingsViewModel: RandomizerSettingsViewModel by activityViewModels()

    private val gson = Gson()

    // Map to hold references to the included row bindings for easier access
    private lateinit var rowBindings: Map<Int, IncludeDicePoolRowBinding> // Key: sides (e.g., 4, 6, 100 for d10_tens, 101 for d10_units)

    // Map to hold the current state being edited
    private var currentDicePool: MutableMap<Int, Int> = mutableMapOf()

    companion object {
        const val TAG = "DicePoolDialogFragment"
        const val PERCENTILE_DIE_TYPE_KEY = 100 // Special key for percentile dice (d100)

        fun newInstance(instanceId: Int): DicePoolDialogFragment {
            return DicePoolDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt("instanceId", instanceId)
                }
            }
        }

        // Constants to represent percentile dice in the map keys
        const val D10_TENS_KEY = 100
        const val D10_UNITS_KEY = 101
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // DialogFragments typically don't inflate a view here when using onCreateDialog
        // However, if you want a custom layout not constrained by AlertDialog, inflate here.
        // For simplicity with AlertDialog, we'll inflate in onCreateDialog.
        // If you needed a fully custom Dialog window, you'd inflate here:
        // _binding = DialogDicePoolBinding.inflate(inflater, container, false)
        // return binding.root
        return super.onCreateView(inflater, container, savedInstanceState) // Should not be called if using onCreateDialog
    }


    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogDicePoolBinding.inflate(LayoutInflater.from(context))

        initializeRowBindings()
        setupListeners()
        observeSettings() // Start observing settings

        // Use AlertDialog.Builder for standard dialog structure
        val builder = AlertDialog.Builder(requireActivity())
        builder.setView(binding.root)
            // Buttons are handled by the included layout's close button now
            // .setPositiveButton("OK") { _, _ -> /* Data is saved on change */ }
            // .setNegativeButton(R.string.cancel) { _, _ -> dismiss() }

        val dialog = builder.create()
        // Optional: Prevent dismissal when touching outside
        // dialog.setCanceledOnTouchOutside(false)
        return dialog
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Clean up binding
    }

    private fun initializeRowBindings() {
        // Map die sides to their corresponding row binding
        rowBindings = mapOf(
            4 to IncludeDicePoolRowBinding.bind(binding.diceRowD4.root),
            6 to IncludeDicePoolRowBinding.bind(binding.diceRowD6.root),
            8 to IncludeDicePoolRowBinding.bind(binding.diceRowD8.root),
            10 to IncludeDicePoolRowBinding.bind(binding.diceRowD10.root),
            D10_TENS_KEY to IncludeDicePoolRowBinding.bind(binding.diceRowD10Tens.root), // Special key for tens
            D10_UNITS_KEY to IncludeDicePoolRowBinding.bind(binding.diceRowD10Units.root), // Special key for units
            12 to IncludeDicePoolRowBinding.bind(binding.diceRowD12.root),
            20 to IncludeDicePoolRowBinding.bind(binding.diceRowD20.root)
        )
    }

    private fun setupListeners() {
        // Set up listeners for each row
        rowBindings.forEach { (sides, rowBinding) ->
            setupRowListeners(sides, rowBinding)
        }

        // Close button listener
        binding.dicePoolCloseButton.setOnClickListener {
            dismiss() // Close the dialog
        }
    }

    private fun setupRowListeners(sides: Int, rowBinding: IncludeDicePoolRowBinding) {
        val maxCount = 10 // Max dice per type

        // Decrement Button
        rowBinding.decrementButton.setOnClickListener {
            val currentCount = currentDicePool[sides] ?: 0
            if (currentCount > 0) {
                updateDiceCount(sides, currentCount - 1, rowBinding.diceCountEditText)
            }
        }

        // Increment Button
        rowBinding.incrementButton.setOnClickListener {
            val currentCount = currentDicePool[sides] ?: 0
            if (currentCount < maxCount) {
                updateDiceCount(sides, currentCount + 1, rowBinding.diceCountEditText)
            }
        }

        // EditText Listener (update after text changes)
        rowBinding.diceCountEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Avoid triggering updates while the ViewModel is populating the field
                if (dialog?.isShowing != true || !rowBinding.diceCountEditText.hasFocus()) return

                val inputCount = s?.toString()?.toIntOrNull() ?: 0
                val currentStoredCount = currentDicePool[sides] ?: 0
                val validatedCount = when {
                    inputCount < 0 -> 0
                    inputCount > maxCount -> maxCount
                    else -> inputCount
                }

                // Only update if the validated number is different from the stored state
                if (validatedCount != currentStoredCount) {
                     // Update the internal state and save
                     updateDiceCount(sides, validatedCount, rowBinding.diceCountEditText, updateEditText = false)
                }

                // If the input was invalid (e.g., > maxCount), reset EditText to validated value
                // Do this check separately to ensure EditText reflects the actual state
                if (inputCount != validatedCount && s.toString() != validatedCount.toString()) {
                     rowBinding.diceCountEditText.setText(validatedCount.toString())
                     rowBinding.diceCountEditText.setSelection(rowBinding.diceCountEditText.text.length) // Move cursor to end
                }
            }
        })
    }


    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.settings.observe(viewLifecycleOwner) { settings ->
                    if (settings != null) {
                        // Parse the JSON config
                        currentDicePool = parseDicePoolConfig(settings.dicePoolConfigJson)?.toMutableMap() ?: mutableMapOf()

                        // Update UI for each die type
                        rowBindings.forEach { (sides, rowBinding) ->
                            val count = currentDicePool[sides] ?: 0
                            // Set text only if it's different to avoid cursor jumps/listener loops
                            if (rowBinding.diceCountEditText.text.toString() != count.toString()) {
                                 rowBinding.diceCountEditText.setText(count.toString())
                            }
                            // Set the label (d4, d6, d%, etc.)
                            setRowLabel(sides, rowBinding.diceTypeLabel)
                        }

                        // Handle visibility of percentile rows
                        val percentileVisible = settings.isPercentileDiceEnabled
                        binding.diceRowD10Tens.root.visibility = if (percentileVisible) View.VISIBLE else View.GONE
                        binding.diceRowD10Units.root.visibility = if (percentileVisible) View.VISIBLE else View.GONE
                        // Hide the standard d10 row if percentile is ON
                        binding.diceRowD10.root.visibility = if (percentileVisible) View.GONE else View.VISIBLE


                    } else {
                        // Handle case where settings are null (e.g., error loading)
                        Log.w(TAG, "Settings are null in DicePoolDialogFragment observer.")
                        // Maybe disable inputs or show an error message within the dialog?
                    }
                }
            }
        }
    }

    private fun setRowLabel(sides: Int, textView: TextView) {
        val label = when (sides) {
            D10_TENS_KEY -> "d% (00-90)" // TODO: String resource
            D10_UNITS_KEY -> "d% (0-9)" // TODO: String resource
            else -> "d$sides"
        }
        textView.text = label
    }

    /** Updates the dice count in the local map, saves to ViewModel, and optionally updates EditText */
    private fun updateDiceCount(sides: Int, newCount: Int, editText: EditText, updateEditText: Boolean = true) {
        // Update local map
        currentDicePool[sides] = newCount

        // Update EditText immediately if requested (usually after button press)
        if (updateEditText && editText.text.toString() != newCount.toString()) {
             editText.setText(newCount.toString())
             // Setting text might trigger afterTextChanged, ensure logic handles it
        }

        // Convert map back to JSON
        val newConfigJson = gson.toJson(currentDicePool)

        // Save through ViewModel
        settingsViewModel.updateDicePoolConfig(newConfigJson) // Need to add this function to ViewModel
    }

    /** Safely parses the dice pool configuration JSON string. */
    private fun parseDicePoolConfig(json: String?): Map<Int, Int>? {
        // Use the same parsing logic as in DiceViewModel for consistency
        if (json.isNullOrEmpty()) {
            Log.w(TAG, "Dice pool config JSON is null or empty, using default.")
            // Attempt to parse the default JSON as a fallback
            return try {
                 val mapType = object : TypeToken<Map<Int, Int>>() {}.type
                 gson.fromJson(DEFAULT_DICE_POOL_JSON, mapType)
            } catch (e: Exception) {
                 Log.e(TAG, "Failed to parse DEFAULT dice pool config JSON", e)
                 emptyMap() // Return empty if even default fails
            }
        }
        return try {
            val mapType = object : TypeToken<Map<Int, Int>>() {}.type
            gson.fromJson(json, mapType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse dice pool config JSON: $json", e)
            null // Indicate parsing failure
        }
    }
}
