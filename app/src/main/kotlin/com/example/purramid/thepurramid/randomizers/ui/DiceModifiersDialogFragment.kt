// DiceModifiersDialogFragment.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.DEFAULT_EMPTY_JSON_MAP
import com.example.purramid.thepurramid.databinding.DialogDiceModifiersBinding
import com.example.purramid.thepurramid.databinding.IncludeDiceModifierRowBinding
import com.example.purramid.thepurramid.randomizers.viewmodel.RandomizerSettingsViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID

@AndroidEntryPoint
class DiceModifiersDialogFragment : DialogFragment() {

    private var _binding: DialogDiceModifiersBinding? = null
    private val binding get() = _binding!!

    private val settingsViewModel: RandomizerSettingsViewModel by activityViewModels()
    private val gson = Gson()

    private lateinit var rowBindings: Map<Int, IncludeDiceModifierRowBinding>
    private var currentDiceModifierConfig: MutableMap<Int, Int> = mutableMapOf()

    // Flag to prevent TextWatcher loops when programmatically setting EditText text
    private var isUpdatingModifierEditTextProgrammatically = false

    companion object {
        const val TAG = "DiceModifiersDialogFragment"

        fun newInstance(instanceId: Int? = null): DiceModifiersDialogFragment {
            return DiceModifiersDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt("instanceId", instanceId ?: 0)
                }
            }
        }
        const val MIN_MODIFIER = 0
        const val MAX_MODIFIER = 100
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogDiceModifiersBinding.inflate(LayoutInflater.from(context))
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
            4 to IncludeDiceModifierRowBinding.bind(binding.diceModifierRowD4.root),
            6 to IncludeDiceModifierRowBinding.bind(binding.diceModifierRowD6.root),
            8 to IncludeDiceModifierRowBinding.bind(binding.diceModifierRowD8.root),
            10 to IncludeDiceModifierRowBinding.bind(binding.diceModifierRowD10.root),
            DicePoolDialogFragment.D10_TENS_KEY to IncludeDiceModifierRowBinding.bind(binding.diceModifierRowD10Tens.root),
            DicePoolDialogFragment.D10_UNITS_KEY to IncludeDiceModifierRowBinding.bind(binding.diceModifierRowD10Units.root),
            12 to IncludeDiceModifierRowBinding.bind(binding.diceModifierRowD12.root),
            20 to IncludeDiceModifierRowBinding.bind(binding.diceModifierRowD20.root)
        )
    }

    private fun setupListeners() {
        binding.diceModifierCloseButton.setOnClickListener { dismiss() }

        rowBindings.forEach { (sides, rowBinding) ->
            setRowLabel(sides, rowBinding.diceTypeLabel)

            // Set a unique tag for each EditText to store its TextWatcher
            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isUpdatingModifierEditTextProgrammatically || dialog?.isShowing != true || !rowBinding.modifierEditText.hasFocus()) {
                        return
                    }

                    val inputText = s?.toString()
                    val inputModifier = inputText?.toIntOrNull()

                    val validatedModifier = when {
                        inputModifier == null && !inputText.isNullOrEmpty() && inputText != "0" -> currentDiceModifierConfig[sides] ?: 0 // Invalid non-empty, revert
                        inputModifier == null -> 0 // Empty or "0" input, treat as 0
                        inputModifier < MIN_MODIFIER -> MIN_MODIFIER
                        inputModifier > MAX_MODIFIER -> MAX_MODIFIER
                        else -> inputModifier
                    }

                    if (validatedModifier != (currentDiceModifierConfig[sides] ?: 0)) {
                        currentDiceModifierConfig[sides] = validatedModifier
                        saveModifierConfig()
                    }

                    if (inputText != validatedModifier.toString()) {
                        isUpdatingModifierEditTextProgrammatically = true
                        rowBinding.modifierEditText.setText(validatedModifier.toString())
                        rowBinding.modifierEditText.setSelection(rowBinding.modifierEditText.text.length)
                        isUpdatingModifierEditTextProgrammatically = false
                    }
                }
            }
            rowBinding.modifierEditText.addTextChangedListener(textWatcher)
        }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.settings.observe(viewLifecycleOwner) { settings ->
                    if (settings != null) {
                        currentDiceModifierConfig = parseDiceModifierConfig(settings.diceModifierConfigJson) ?: mutableMapOf()
                        updateModifierUI() // This will populate EditTexts

                        val percentileEnabled = settings.isPercentileDiceEnabled
                        binding.diceModifierRowD10Tens.root.visibility = if (percentileEnabled) View.VISIBLE else View.GONE
                        binding.diceModifierRowD10Units.root.visibility = if (percentileEnabled) View.VISIBLE else View.GONE
                        binding.diceModifierRowD10.root.visibility = if (percentileEnabled) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    private fun updateModifierUI() {
        isUpdatingModifierEditTextProgrammatically = true // Prevent listeners while populating
        rowBindings.forEach { (sides, rowBinding) ->
            val modifier = currentDiceModifierConfig[sides] ?: 0 // Default to 0
            if (rowBinding.modifierEditText.text.toString() != modifier.toString()) {
                rowBinding.modifierEditText.setText(modifier.toString())
            }
        }
        isUpdatingModifierEditTextProgrammatically = false
    }

    private fun setRowLabel(sides: Int, textView: TextView) {
        val label = when (sides) {
            DicePoolDialogFragment.D10_TENS_KEY -> getString(R.string.dice_label_d10_tens)
            DicePoolDialogFragment.D10_UNITS_KEY -> getString(R.string.dice_label_d10_units)
            else -> "d$sides"
        }
        textView.text = label
    }

    private fun saveModifierConfig() {
        val newConfigJson = gson.toJson(currentDiceModifierConfig)
        settingsViewModel.updateDiceModifierConfig(newConfigJson)
    }

    private fun parseDiceModifierConfig(json: String?): MutableMap<Int, Int>? {
        if (json.isNullOrEmpty() || json == DEFAULT_EMPTY_JSON_MAP) {
            return mutableMapOf()
        }
        return try {
            val mapType = object : TypeToken<MutableMap<Int, Int>>() {}.type
            gson.fromJson(json, mapType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse dice modifier config JSON: $json", e)
            mutableMapOf()
        }
    }
}
