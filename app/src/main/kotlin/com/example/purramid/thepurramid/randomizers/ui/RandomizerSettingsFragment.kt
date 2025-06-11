// RandomizerSettingsFragment.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.RandomizerDao
import com.example.purramid.thepurramid.data.db.RandomizerInstanceEntity
import com.example.purramid.thepurramid.data.db.SpinSettingsEntity
import com.example.purramid.thepurramid.databinding.FragmentRandomizerSettingsBinding
import com.example.purramid.thepurramid.randomizers.DiceSumResultType
import com.example.purramid.thepurramid.randomizers.GraphDistributionType
import com.example.purramid.thepurramid.randomizers.PlotType // Your new enum
import com.example.purramid.thepurramid.randomizers.RandomizerMode
import com.example.purramid.thepurramid.randomizers.RandomizersHostActivity
import com.example.purramid.thepurramid.randomizers.viewmodel.RandomizerSettingsViewModel
import com.example.purramid.thepurramid.ui.PurramidPalette // For color picker
import com.google.android.material.chip.Chip
import com.google.android.material.colorpicker.MaterialColorPickerDialog
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class RandomizerSettingsFragment : Fragment() {

    private var _binding: FragmentRandomizerSettingsBinding? = null
    private val binding get() = _binding!!

    private val settingsViewModel: RandomizerSettingsViewModel by activityViewModels()
    private val args: RandomizerSettingsFragmentArgs by navArgs()

    private lateinit var currentSettingsEntity: SpinSettingsEntity
    private var initialBackgroundColor: Int = Color.BLACK // Default or from palette

    @Inject
    lateinit var randomizerDao: RandomizerDao // Injected for "Add Another" functionality

    companion object {
        private const val TAG = "RandomizerSettingsFrag"
        private const val MAX_RANDOMIZER_INSTANCES = 4
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRandomizerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated for instanceId: ${args.instanceId}")

        observeSettings()
        setupListeners()
        updateAddAnotherButtonState()
    }

    private fun observeSettings() {
        settingsViewModel.settings.observe(viewLifecycleOwner) { settings ->
            if (settings == null) {
                Log.e(TAG, "Settings are null for instanceId: ${args.instanceId}. Attempting to re-load or use defaults.")
                // Attempt to re-trigger load or create defaults if this state is unexpected.
                // For now, we might disable UI or show error.
                // binding.mainSettingsContainer.isVisible = false // Example
                return@observe
            }
            Log.d(TAG, "Settings observed: ${settings.instanceId}, Mode: ${settings.mode}")
            currentSettingsEntity = settings
            initialBackgroundColor = settings.backgroundColor
            updateUiWithSettings()
        }

        settingsViewModel.errorEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { errorMessage ->
                Snackbar.make(binding.root, errorMessage, Snackbar.LENGTH_LONG).show()
            }
        }
        // If instanceId is passed via navArgs, ViewModel uses SavedStateHandle to get it and load.
        // Explicit call to settingsViewModel.loadSettings(args.instanceId) is generally not needed
        // if ViewModel's init block handles loading via SavedStateHandle.
    }

    private fun updateUiWithSettings() {
        if (!::currentSettingsEntity.isInitialized) {
            Log.w(TAG, "updateUiWithSettings called before currentSettingsEntity is initialized.")
            return
        }

        binding.mainSettingsContainer.isVisible = true // Show UI once settings are loaded

        // Mode Selector
        binding.modeChipGroup.setOnCheckedChangeListener(null) // Temporarily remove listener
        val modeChipId = getChipIdForMode(currentSettingsEntity.mode)
        if (modeChipId != -1) {
            binding.modeChipGroup.check(modeChipId)
        }
        binding.modeChipGroup.setOnCheckedChangeListener(modeChipChangeListener)

        // Show/Hide mode-specific setting groups
        binding.spinSettingsGroup.isVisible = currentSettingsEntity.mode == RandomizerMode.SPIN
        binding.slotsSettingsGroup.isVisible = currentSettingsEntity.mode == RandomizerMode.SLOTS
        binding.diceSettingsGroup.isVisible = currentSettingsEntity.mode == RandomizerMode.DICE
        binding.coinFlipSettingsLayout.isVisible = currentSettingsEntity.mode == RandomizerMode.COIN_FLIP

        // Background Color Picker Button
        updateBackgroundColorButton(currentSettingsEntity.backgroundColor)

        // Populate Spin Settings
        if (currentSettingsEntity.mode == RandomizerMode.SPIN) {
            binding.switchSpinSoundEnabled.isChecked = currentSettingsEntity.isSoundEnabled
            binding.switchSpinResultAnnouncement.isChecked = currentSettingsEntity.isAnnounceEnabled
            binding.switchSpinSequenceEnabled.isChecked = currentSettingsEntity.isSequenceEnabled
            binding.switchSpinConfetti.isChecked = currentSettingsEntity.isConfettiEnabled
            binding.textFieldSpinDuration.setText(currentSettingsEntity.spinDurationMillis.toString())
            binding.textFieldSpinMaxItems.setText(currentSettingsEntity.spinMaxItems.toString())
        }

        // When sequence is enabled, confetti and announcement might be disabled
        if (currentSettingsEntity.isSequenceEnabled) {
            binding.switchSpinResultAnnouncement.isEnabled = false
            binding.switchSpinConfetti.isEnabled = false
        } else {
            binding.switchSpinResultAnnouncement.isEnabled = true
            // Confetti enablement depends on announcement, see section C
            binding.switchSpinConfetti.isEnabled = currentSettingsEntity.isAnnounceEnabled
        }

        // Populate Slots Settings
        if (currentSettingsEntity.mode == RandomizerMode.SLOTS) {
            binding.switchSlotsSound.isChecked = currentSettingsEntity.isSlotsSoundEnabled
            binding.switchSlotsResultAnnouncement.isChecked = currentSettingsEntity.isSlotsAnnounceResultEnabled
            binding.textFieldSlotsSpinDuration.setText(currentSettingsEntity.slotsSpinDuration.toString())
            binding.textFieldSlotsReelVariation.setText(currentSettingsEntity.slotsReelStopVariation.toString())
        }
        when (currentSettingsEntity.numSlotsColumns) {
            3 -> binding.toggleSlotsColumns.check(R.id.buttonSlotsColumns3)
            5 -> binding.toggleSlotsColumns.check(R.id.buttonSlotsColumns5)
            else -> binding.toggleSlotsColumns.check(R.id.buttonSlotsColumns3) // Default
        }

        // Populate Dice Settings
        if (currentSettingsEntity.mode == RandomizerMode.DICE) {
            binding.switchDiceAnimationEnabled.isChecked = currentSettingsEntity.isDiceAnimationEnabled
            binding.switchDiceSumResultsEnabled.isChecked = currentSettingsEntity.isDiceSumResultsEnabled
            binding.switchDicePips.isChecked = currentSettingsEntity.useDicePips
            binding.switchDiceResultAnnouncement.isChecked = currentSettingsEntity.isAnnounceEnabled
            binding.switchDiceCritCelebration.isChecked = currentSettingsEntity.isDiceCritCelebrationEnabled

            binding.switchDiceGraphEnabled.isChecked = currentSettingsEntity.isDiceGraphEnabled
            val diceGraphOptionsVisibility = if (currentSettingsEntity.isDiceGraphEnabled) View.VISIBLE else View.GONE

            binding.diceGraphPlotTypeLayout.visibility = diceGraphOptionsVisibility
            binding.diceGraphDistributionTypeLayout.visibility = diceGraphOptionsVisibility
            binding.diceGraphFlipCountLayout.visibility = diceGraphOptionsVisibility

            if (currentSettingsEntity.isDiceGraphEnabled) {
                setupGraphPlotTypeDropdown(
                    autoCompleteView = binding.diceGraphPlotTypeDropDown,
                    currentPlotTypeString = currentSettingsEntity.diceGraphPlotType,
                    defaultPlotType = PlotType.HISTOGRAM,
                    isDiceMode = true // Indicate it's for Dice
                )
                setupGraphDistributionTypeDropdown(
                    autoCompleteView = binding.diceGraphDistributionTypeDropDown,
                    currentDistributionTypeString = currentSettingsEntity.diceGraphDistributionType,
                    defaultDistributionType = GraphDistributionType.SUM_OF_ALL_DICE,
                    isDiceMode = true
                )
                binding.textFieldDiceGraphFlipCount.setText(currentSettingsEntity.diceGraphFlipCount.takeIf { it > 0 }?.toString() ?: "")
            }
        }

        // Populate Coin Flip Settings
        if (currentSettingsEntity.mode == RandomizerMode.COIN_FLIP) {
            binding.switchCoinFlipAnimationEnabled.isChecked = currentSettingsEntity.isFlipAnimationEnabled
            binding.switchCoinFreeFormEnabled.isChecked = currentSettingsEntity.isCoinFreeFormEnabled
            binding.switchCoinResultAnnouncement.isChecked = currentSettingsEntity.isCoinAnnouncementEnabled
            binding.textFieldCoinColor.setText(String.format("#%06X", 0xFFFFFF and currentSettingsEntity.coinColor)) // Show hex

            binding.switchCoinGraphEnabled.isChecked = currentSettingsEntity.isCoinGraphEnabled
            val coinGraphOptionsVisibility = if (currentSettingsEntity.isCoinGraphEnabled) View.VISIBLE else View.GONE

            binding.menuCoinGraphPlotTypeLayout.visibility = coinGraphOptionsVisibility // Ensure this ID exists for coin plot type layout
            binding.coinGraphDistributionTypeLayout.visibility = coinGraphOptionsVisibility
            binding.coinGraphFlipCountLayout.visibility = coinGraphOptionsVisibility

            if (currentSettingsEntity.isCoinGraphEnabled) {
                setupGraphPlotTypeDropdown(
                    autoCompleteView = binding.autoCompleteCoinGraphPlotType, // Use your actual ID for coin plot type dropdown
                    currentPlotTypeString = currentSettingsEntity.coinGraphPlotType,
                    defaultPlotType = PlotType.HISTOGRAM,
                    isDiceMode = false // Indicate it's for Coin
                )
                setupGraphDistributionTypeDropdown(
                    autoCompleteView = binding.coinGraphDistributionTypeDropDown, // Use your actual ID
                    currentDistributionTypeString = currentSettingsEntity.coinGraphDistributionType,
                    defaultDistributionType = GraphDistributionType.NONE, // Or appropriate default for coins
                    isDiceMode = false
                )
                binding.textFieldCoinGraphFlipCount.setText(currentSettingsEntity.coinGraphFlipCount.takeIf { it > 0 }?.toString() ?: "")
            }
        }
    }

    private fun setupGraphPlotTypeDropdown(
        autoCompleteView: AutoCompleteTextView,
        currentPlotTypeString: String?,
        defaultPlotType: PlotType,
        isDiceMode: Boolean // To update the correct field in currentSettingsEntity
    ) {
        val plotTypes = PlotType.values()
        val plotTypeDisplayNames = plotTypes.map {
            when (it) {
                PlotType.HISTOGRAM -> getString(R.string.plot_type_histogram)
                PlotType.LINE_GRAPH -> getString(R.string.plot_type_line_graph)
                PlotType.QQ_PLOT -> getString(R.string.plot_type_qq_graph)
            }
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, plotTypeDisplayNames)
        autoCompleteView.setAdapter(adapter)

        val currentPlotType = PlotType.values().find { it.name == currentPlotTypeString } ?: defaultPlotType
        autoCompleteView.setText(plotTypeDisplayNames[plotTypes.indexOf(currentPlotType)], false)

        // Listener is set in setupSpecificListeners to avoid issues with adapter re-creation
    }


    private fun setupGraphDistributionTypeDropdown(
        autoCompleteView: AutoCompleteTextView,
        currentDistributionTypeString: String?,
        defaultDistributionType: GraphDistributionType,
        isDiceMode: Boolean
    ) {
        val distributionTypes = GraphDistributionType.values()
        val displayNames = distributionTypes.map { it.name.replace("_", " ").capitalizeWords() } // Simple formatting

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, displayNames)
        autoCompleteView.setAdapter(adapter)

        val currentType = GraphDistributionType.values().find { it.name == currentDistributionTypeString } ?: defaultDistributionType
        autoCompleteView.setText(displayNames[distributionTypes.indexOf(currentType)], false)
    }


    private val modeChipChangeListener = CompoundButton.OnCheckedChangeListener { chip, isChecked ->
        if (isChecked && ::currentSettingsEntity.isInitialized) {
            val newMode = when (chip.id) {
                R.id.chipSpin -> RandomizerMode.SPIN
                R.id.chipSlots -> RandomizerMode.SLOTS
                R.id.chipDice -> RandomizerMode.DICE
                R.id.chipCoinFlip -> RandomizerMode.COIN_FLIP
                else -> currentSettingsEntity.mode // Should not happen
            }
            if (currentSettingsEntity.mode != newMode) {
                Log.d(TAG, "Mode changed to: $newMode")
                currentSettingsEntity = currentSettingsEntity.copy(mode = newMode)
                settingsViewModel.updateMode(newMode) // Update ViewModel which should persist and reload settings for new mode structure
                // updateUiWithSettings() will be called by the observer on settingsViewModel.settings
            }
        }
    }

    private fun setupListeners() {
        binding.closeSettingsButton.setOnClickListener { onCloseSettingsClicked() }
        binding.buttonAddAnotherRandomizer.setOnClickListener { addAnotherRandomizer() }

        binding.modeChipGroup.setOnCheckedChangeListener(modeChipChangeListener)

        // Background Color Picker
        binding.buttonChangeBackgroundColor.setOnClickListener { openColorPicker() }

        // Spin Settings Listeners
        binding.switchSpinSoundEnabled.setOnCheckedChangeListener { _, isChecked -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(isSoundEnabled = isChecked) }
        binding.switchSpinResultAnnouncement.setOnCheckedChangeListener { _, isChecked ->
            if (!::currentSettingsEntity.isInitialized) return@setOnCheckedChangeListener
            currentSettingsEntity = currentSettingsEntity.copy(isAnnounceEnabled = isChecked)
            if (binding.switchSpinSequenceEnabled.isChecked) { // Sequence mode takes precedence
                binding.switchSpinConfetti.isEnabled = false
                return@setOnCheckedChangeListener
            }
            binding.switchSpinConfetti.isEnabled = isChecked
            if (!isChecked) { // If announcement is turned off
                binding.switchSpinConfetti.isChecked = false // Turn off confetti as well
                currentSettingsEntity = currentSettingsEntity.copy(isConfettiEnabled = false)
            }
        }
        binding.switchSpinSequenceEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!::currentSettingsEntity.isInitialized) return@setOnCheckedChangeListener
            currentSettingsEntity = currentSettingsEntity.copy(isSequenceEnabled = isChecked)
            // Update dependent controls
            if (isChecked) {
                binding.switchSpinResultAnnouncement.isEnabled = false
                binding.switchSpinResultAnnouncement.isChecked = false // Optionally turn off
                binding.switchSpinConfetti.isEnabled = false
                binding.switchSpinConfetti.isChecked = false // Optionally turn off
            } else {
                binding.switchSpinResultAnnouncement.isEnabled = true
                // Re-evaluate confetti enablement based on announcement
                binding.switchSpinConfetti.isEnabled = binding.switchSpinResultAnnouncement.isChecked
            }
        }
        binding.switchSpinConfetti.setOnCheckedChangeListener { _, isChecked ->
            if (!::currentSettingsEntity.isInitialized) return@setOnCheckedChangeListener
            // Only allow confetti to be checked if announcement is enabled and sequence is not
            if (binding.switchSpinResultAnnouncement.isChecked && !binding.switchSpinSequenceEnabled.isChecked) {
                currentSettingsEntity = currentSettingsEntity.copy(isConfettiEnabled = isChecked)
            } else {
                // If conditions not met, force confetti to off
                (it as CompoundButton).isChecked = false
                currentSettingsEntity = currentSettingsEntity.copy(isConfettiEnabled = false)
            }
        }
        binding.textFieldSpinDuration.doAfterTextChanged { text -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(spinDurationMillis = text.toString().toLongOrNull() ?: 2000L) }
        binding.textFieldSpinMaxItems.doAfterTextChanged { text -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(spinMaxItems = text.toString().toIntOrNull() ?: 20) }
        binding.buttonEditSpinList.setOnClickListener {
            if (!::currentSettingsEntity.isInitialized) return@setOnClickListener
            val instanceIdString = currentSettingsEntity.instanceId.toString()
            val listIdToEdit = currentSettingsEntity.currentSpinListId
            // You'll need a NavAction that can take both instanceId and an optional listId
            // If listIdToEdit is null, ListEditorActivity treats it as "create new for this instance"
            // If listIdToEdit is not null, ListEditorActivity loads that list.
            val action = RandomizerSettingsFragmentDirections.actionRandomizerSettingsFragmentToListEditorActivity(
                instanceId = instanceIdString,
                listId = listIdToEdit ?: -1L // Pass -1L or another indicator for "new list" if your editor expects a Long
            )
            findNavController().navigate(action)
        }

        // Slots Settings Listeners
        binding.switchSlotsSound.setOnCheckedChangeListener { _, isChecked -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(isSlotsSoundEnabled = isChecked) }
        binding.switchSlotsResultAnnouncement.setOnCheckedChangeListener { _, isChecked -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(isSlotsAnnounceResultEnabled = isChecked) }
        binding.textFieldSlotsSpinDuration.doAfterTextChanged { text -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(slotsSpinDuration = text.toString().toLongOrNull() ?: 1000L) }
        binding.textFieldSlotsReelVariation.doAfterTextChanged { text -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(slotsReelStopVariation = text.toString().toLongOrNull() ?: 200L) }
        binding.buttonEditSlotsLists.setOnClickListener { /* TODO: Navigate to Slots List Editor */ }
        binding.toggleSlotsColumns.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked && ::currentSettingsEntity.isInitialized) {
                val newColumnCount = when (checkedId) {
                    R.id.buttonSlotsColumns3 -> 3
                    R.id.buttonSlotsColumns5 -> 5
                    else -> currentSettingsEntity.numSlotsColumns // Should not happen
                }
                if (currentSettingsEntity.numSlotsColumns != newColumnCount) {
                    currentSettingsEntity = currentSettingsEntity.copy(numSlotsColumns = newColumnCount)
                }
            }
        }

        // Dice Settings Listeners
        binding.switchDiceAnimationEnabled.setOnCheckedChangeListener { _, isChecked -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(isDiceAnimationEnabled = isChecked) }
        binding.switchDiceSumResultsEnabled.setOnCheckedChangeListener { _, isChecked -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(isDiceSumResultsEnabled = isChecked) }
        binding.switchDicePips.setOnCheckedChangeListener { _, isChecked -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(useDicePips = isChecked) }
        binding.switchDiceResultAnnouncement.setOnCheckedChangeListener { _, isChecked -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(isAnnounceEnabled = isChecked) } // Re-uses isAnnounceEnabled
        binding.switchDiceCritCelebration.setOnCheckedChangeListener { _, isChecked -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(isDiceCritCelebrationEnabled = isChecked) }
        binding.buttonManageDicePool.setOnClickListener { if (::currentSettingsEntity.isInitialized) DicePoolDialogFragment.newInstance(currentSettingsEntity.instanceId).show(parentFragmentManager, DicePoolDialogFragment.TAG) }
        binding.buttonManageDiceModifiers.setOnClickListener { if (::currentSettingsEntity.isInitialized) DiceModifiersDialogFragment.newInstance(currentSettingsEntity.instanceId).show(parentFragmentManager, DiceModifiersDialogFragment.TAG) }
        binding.buttonManageDiceColors.setOnClickListener { if (::currentSettingsEntity.isInitialized) DiceColorPickerDialogFragment.newInstance(currentSettingsEntity.instanceId).show(parentFragmentManager, DiceColorPickerDialogFragment.TAG)}

        binding.switchDiceGraphEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (::currentSettingsEntity.isInitialized) {
                currentSettingsEntity = currentSettingsEntity.copy(isDiceGraphEnabled = isChecked)
                val visibility = if (isChecked) View.VISIBLE else View.GONE
                binding.diceGraphPlotTypeLayout.visibility = visibility
                binding.diceGraphDistributionTypeLayout.visibility = visibility
                binding.diceGraphFlipCountLayout.visibility = visibility
                if (isChecked) { // If enabling, ensure dropdowns are populated/refreshed
                    setupGraphPlotTypeDropdown(binding.diceGraphPlotTypeDropDown, currentSettingsEntity.diceGraphPlotType, PlotType.HISTOGRAM, true)
                    setupGraphDistributionTypeDropdown(binding.diceGraphDistributionTypeDropDown, currentSettingsEntity.diceGraphDistributionType, GraphDistributionType.SUM_OF_ALL_DICE, true)
                }
            }
        }
        binding.diceGraphPlotTypeDropDown.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (::currentSettingsEntity.isInitialized) {
                val selectedPlotType = PlotType.values()[position]
                currentSettingsEntity = currentSettingsEntity.copy(diceGraphPlotType = selectedPlotType.name)
            }
        }
        binding.diceGraphDistributionTypeDropDown.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (::currentSettingsEntity.isInitialized) {
                val selectedDistType = GraphDistributionType.values()[position]
                currentSettingsEntity = currentSettingsEntity.copy(diceGraphDistributionType = selectedDistType.name)
            }
        }
        binding.textFieldDiceGraphFlipCount.doAfterTextChanged { text ->
            if (::currentSettingsEntity.isInitialized) {
                currentSettingsEntity = currentSettingsEntity.copy(diceGraphFlipCount = text.toString().toIntOrNull() ?: 0)
            }
        }

        // Coin Flip Settings Listeners
        binding.switchCoinFlipAnimationEnabled.setOnCheckedChangeListener { _, isChecked -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(isFlipAnimationEnabled = isChecked) }
        binding.switchCoinFreeFormEnabled.setOnCheckedChangeListener { _, isChecked -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(isCoinFreeFormEnabled = isChecked) }
        binding.switchCoinResultAnnouncement.setOnCheckedChangeListener { _, isChecked -> if (::currentSettingsEntity.isInitialized) currentSettingsEntity = currentSettingsEntity.copy(isCoinAnnouncementEnabled = isChecked) }
        binding.textFieldCoinColor.doOnTextChanged { text, _, _, _ -> /* Parsed in openCoinColorPicker */ }
        binding.buttonPickCoinColor.setOnClickListener { if (::currentSettingsEntity.isInitialized) openCoinColorPicker() }
        binding.buttonManageCoinPool.setOnClickListener { if (::currentSettingsEntity.isInitialized) CoinPoolDialogFragment.newInstance(currentSettingsEntity.instanceId).show(parentFragmentManager, CoinPoolDialogFragment.TAG) }


        binding.switchCoinGraphEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (::currentSettingsEntity.isInitialized) {
                currentSettingsEntity = currentSettingsEntity.copy(isCoinGraphEnabled = isChecked)
                val visibility = if (isChecked) View.VISIBLE else View.GONE
                binding.menuCoinGraphPlotTypeLayout.visibility = visibility // Use your actual ID
                binding.coinGraphDistributionTypeLayout.visibility = visibility
                binding.coinGraphFlipCountLayout.visibility = visibility
                if (isChecked) { // If enabling, ensure dropdowns are populated/refreshed
                    setupGraphPlotTypeDropdown(binding.autoCompleteCoinGraphPlotType, currentSettingsEntity.coinGraphPlotType, PlotType.HISTOGRAM, false)
                    setupGraphDistributionTypeDropdown(binding.coinGraphDistributionTypeDropDown, currentSettingsEntity.coinGraphDistributionType, GraphDistributionType.NONE, false)
                }
            }
        }
        binding.autoCompleteCoinGraphPlotType.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ -> // Use your actual ID
            if (::currentSettingsEntity.isInitialized) {
                val selectedPlotType = PlotType.values()[position]
                currentSettingsEntity = currentSettingsEntity.copy(coinGraphPlotType = selectedPlotType.name)
            }
        }
        binding.coinGraphDistributionTypeDropDown.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ -> // Use your actual ID
            if (::currentSettingsEntity.isInitialized) {
                val selectedDistType = GraphDistributionType.values()[position]
                currentSettingsEntity = currentSettingsEntity.copy(coinGraphDistributionType = selectedDistType.name)
            }
        }
        binding.textFieldCoinGraphFlipCount.doAfterTextChanged { text ->
            if (::currentSettingsEntity.isInitialized) {
                currentSettingsEntity = currentSettingsEntity.copy(coinGraphFlipCount = text.toString().toIntOrNull() ?: 0)
            }
        }
    }

    private fun updateBackgroundColorButton(color: Int) {
        val drawable = ContextCompat.getDrawable(requireContext(), R.drawable.color_square_background) as? GradientDrawable
        drawable?.setColor(color)
        binding.buttonChangeBackgroundColor.icon = drawable
        binding.textFieldRandomizerBackgroundColor.setText(String.format("#%06X", 0xFFFFFF and color))
    }

    private fun openColorPicker() {
        val initialColor = currentSettingsEntity.backgroundColor
        val colorPicker = MaterialColorPickerDialog.Builder(requireContext())
            .setTitle("Choose Background Color")
            .setColor(initialColor)
            .setListener { color, _ ->
                currentSettingsEntity = currentSettingsEntity.copy(backgroundColor = color)
                updateBackgroundColorButton(color)
            }
            .show()
    }
    private fun openCoinColorPicker() {
        val initialColor = currentSettingsEntity.coinColor
        val colorPicker = MaterialColorPickerDialog.Builder(requireContext())
            .setTitle("Choose Coin Color")
            .setColor(initialColor)
            .setListener { color, _ ->
                currentSettingsEntity = currentSettingsEntity.copy(coinColor = color)
                binding.textFieldCoinColor.setText(String.format("#%06X", 0xFFFFFF and color))
            }
            .show()
    }


    private fun getChipIdForMode(mode: RandomizerMode): Int {
        return when (mode) {
            RandomizerMode.SPIN -> R.id.chipSpin
            RandomizerMode.SLOTS -> R.id.chipSlots
            RandomizerMode.DICE -> R.id.chipDice
            RandomizerMode.COIN_FLIP -> R.id.chipCoinFlip
        }
    }

    private fun saveCurrentSettings() {
        if (::currentSettingsEntity.isInitialized) {
            Log.d(TAG, "Saving settings for instance ${currentSettingsEntity.instanceId}")
            settingsViewModel.saveSettings(currentSettingsEntity)
        } else {
            Log.w(TAG, "saveCurrentSettings called but currentSettingsEntity not initialized.")
        }
    }

    private fun onCloseSettingsClicked() {
        saveCurrentSettings()
        activity?.finish() // Finishes the RandomizersHostActivity
    }

    override fun onPause() {
        super.onPause()
        saveCurrentSettings()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateAddAnotherButtonState() {
        viewLifecycleOwner.lifecycleScope.launch {
            val activeInstances = withContext(Dispatchers.IO) {
                randomizerDao.getActiveInstancesCount()
            }
            binding.buttonAddAnotherRandomizer.isEnabled = activeInstances < MAX_RANDOMIZER_INSTANCES
            Log.d(TAG, "Active instances: $activeInstances, Add Another button enabled: ${binding.buttonAddAnotherRandomizer.isEnabled}")
        }
    }

    private fun addAnotherRandomizer() {
        if (!::currentSettingsEntity.isInitialized) {
            Snackbar.make(binding.root, "Current settings not loaded, cannot clone.", Snackbar.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val activeInstances = withContext(Dispatchers.IO) { randomizerDao.getActiveInstancesCount() }
            if (activeInstances >= MAX_RANDOMIZER_INSTANCES) {
                Snackbar.make(binding.root, "Maximum number of randomizer windows reached.", Snackbar.LENGTH_LONG).show()
                return@launch
            }

            // Save current settings BEFORE cloning, so the clone gets the latest changes.
            settingsViewModel.saveSettings(currentSettingsEntity)

            val newInstanceId = UUID.randomUUID()
            val newSettings = currentSettingsEntity.copy(
                instanceId = newInstanceId,
                // Reset any instance-specific states if necessary
                slotsColumnStates = emptyList(), // Example
                // Consider if graph data should be cloned or reset for the new instance
                // For now, graph-related settings are cloned, but accumulated graph data is not.
                // Resetting fields like spin list ID, slots list ID if they shouldn't be shared.
                currentSpinListId = null // Example: New instance starts with no list selected
            )
            val newInstanceEntity = RandomizerInstanceEntity(instanceId = newInstanceId)

            try {
                withContext(Dispatchers.IO) {
                    randomizerDao.saveSettings(newSettings) // Save settings for the NEW instance
                    randomizerDao.saveInstance(newInstanceEntity) // Save the NEW instance record
                }
                Log.d(TAG, "Cloned settings from ${currentSettingsEntity.instanceId} and created new instance: $newInstanceId")

                // Launch the new RandomizersHostActivity with the NEW instance ID
                (activity as? MainActivity)?.launchNewRandomizerInstanceWithBounds(newInstanceId.toString())


                // The RandomizerSettingsViewModel has a cloneSettingsForNewInstance method.
                // The current logic in this fragment directly creates and saves the new settings.
                // This is a valid approach. The ViewModel's method might be for a different cloning trigger.

                updateAddAnotherButtonState() // Refresh button state
                // Potentially close this settings window or navigate, depending on UX desired
                // activity?.finish() // Closes the current RandomizersHostActivity window

            } catch (e: Exception) {
                Log.e(TAG, "Error saving new cloned instance or launching activity", e)
                Snackbar.make(binding.root, "Error creating new randomizer window.", Snackbar.LENGTH_LONG).show()
            }
        }
    }
}

// Helper extension function for String capitalization
fun String.capitalizeWords(): String = split(" ").joinToString(" ") { it.lowercase().replaceFirstChar(Char::titlecase) }