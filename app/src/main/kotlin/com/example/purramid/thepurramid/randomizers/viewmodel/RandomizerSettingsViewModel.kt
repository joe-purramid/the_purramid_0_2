// RandomizerSettingsViewModel.kt
package com.example.purramid.thepurramid.randomizers.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.RandomizerDao
import com.example.purramid.thepurramid.data.db.SpinSettingsEntity
import com.example.purramid.thepurramid.data.db.DEFAULT_COIN_COLOR_INT // Import default coin color
import com.example.purramid.thepurramid.randomizers.DiceSumResultType
import com.example.purramid.thepurramid.randomizers.GraphDistributionType
import com.example.purramid.thepurramid.randomizers.GraphPlotType
import com.example.purramid.thepurramid.randomizers.RandomizerMode
import com.example.purramid.thepurramid.randomizers.CoinProbabilityMode // Import new Enum
import com.example.purramid.thepurramid.util.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class RandomizerSettingsViewModel @Inject constructor(
    private val randomizerDao: RandomizerDao,
    savedStateHandle: SavedStateHandle // Hilt injects this automatically
) : ViewModel() {

    companion object {
        const val KEY_INSTANCE_ID = "instanceId" // Ensure this matches NavArgs key
        private const val TAG = "SettingsViewModel"
    }

    private val instanceId: UUID? = savedStateHandle.get<String>(KEY_INSTANCE_ID)?.let {
        try { UUID.fromString(it) } catch (e: IllegalArgumentException) { null }
    }

    private val _settings = MutableLiveData<SpinSettingsEntity?>()
    val settings: LiveData<SpinSettingsEntity?> = _settings

    private val _errorEvent = MutableLiveData<Event<Int>>() // Using Int for String Resource ID
    val errorEvent: LiveData<Event<Int>> = _errorEvent

    init {
        if (instanceId != null) {
            loadSettings(instanceId)
        } else {
            Log.e(TAG, "Critical Error: Instance ID is null in SavedStateHandle.")
            _errorEvent.postValue(Event(R.string.error_settings_instance_id_failed))
            _settings.postValue(null)
        }
    }

    private fun loadSettings(id: UUID) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loadedSettings = randomizerDao.getSettingsForInstance(id)
                if (loadedSettings != null) {
                    withContext(Dispatchers.Main) { _settings.value = loadedSettings }
                } else {
                    Log.w(TAG, "No settings found for instance $id. Creating new default settings.")
                    // Ensure all defaults, including new coin flip ones, are set
                    val defaultSettings = SpinSettingsEntity(
                        instanceId = id,
                        mode = RandomizerMode.SPIN, // Default mode for a new instance
                        coinColor = DEFAULT_COIN_COLOR_INT,
                        isFlipAnimationEnabled = true,
                        isCoinFreeFormEnabled = false,
                        isCoinAnnouncementEnabled = true,
                        coinProbabilityMode = CoinProbabilityMode.NONE.name,
                        coinGraphDistributionType = GraphDistributionType.OFF.name,
                        coinGraphPlotType = GraphPlotType.HISTOGRAM.name,
                        coinGraphFlipCount = 1000
                    )
                    randomizerDao.saveSettings(defaultSettings)
                    withContext(Dispatchers.Main) {
                        _settings.value = defaultSettings
                        _errorEvent.value = Event(R.string.info_settings_defaulted_kitty)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading settings for instance $id", e)
                withContext(Dispatchers.Main) {
                    _errorEvent.value = Event(R.string.error_settings_load_failed)
                    _settings.value = null
                }
            }
        }
    }

    private fun updateSettingsField(updateAction: (SpinSettingsEntity) -> SpinSettingsEntity) {
        val currentSettings = _settings.value
        if (currentSettings == null) {
            Log.e(TAG, "Attempted to update settings but current settings are null.")
            _errorEvent.postValue(Event(R.string.error_settings_not_loaded_cant_save))
            return
        }
        // Apply the update action and then save
        saveSettings(updateAction(currentSettings))
    }

    fun updateMode(newMode: RandomizerMode) {
        updateSettingsField { it.copy(mode = newMode) }
    }

    fun updateNumSlotsColumns(numColumns: Int) {
        if (numColumns == 3 || numColumns == 5) {
            updateSettingsField { it.copy(numSlotsColumns = numColumns) }
        }
    }

    fun updateUseDicePips(enabled: Boolean) {
        updateSettingsField { it.copy(useDicePips = enabled) }
    }

    fun updateIsPercentileDiceEnabled(enabled: Boolean) {
        updateSettingsField { it.copy(isPercentileDiceEnabled = enabled) }
    }

    fun updateIsDiceAnimationEnabled(enabled: Boolean) {
        updateSettingsField { it.copy(isDiceAnimationEnabled = enabled) }
    }

    fun updateDiceSumResultType(newType: DiceSumResultType) {
        updateSettingsField { it.copy(diceSumResultType = newType) }
    }

    fun updateIsDiceCritCelebrationEnabled(enabled: Boolean) {
        updateSettingsField { settings ->
            val announceIsOn = settings.isAnnounceEnabled && settings.mode == RandomizerMode.DICE
            settings.copy(isDiceCritCelebrationEnabled = enabled && announceIsOn)
        }
    }

    fun updateIsAnnounceEnabled(enabled: Boolean) {
        updateSettingsField { settings ->
            var updatedSettings = settings.copy(isAnnounceEnabled = enabled)
            if (enabled) { // Turning Announce ON
                // Mutually exclusive: Turn off Probability and Free Form for Coin Flip
                if (settings.mode == RandomizerMode.COIN_FLIP) {
                    updatedSettings = updatedSettings.copy(
                        coinProbabilityMode = CoinProbabilityMode.NONE.name,
                        isCoinFreeFormEnabled = false
                    )
                }
                // Mutually exclusive with Graph for Dice
                if (settings.mode == RandomizerMode.DICE) {
                    updatedSettings = updatedSettings.copy(graphDistributionType = GraphDistributionType.OFF)
                }
                // Mutually exclusive with Sequence for Spin
                if (settings.mode == RandomizerMode.SPIN) {
                    updatedSettings = updatedSettings.copy(isSequenceEnabled = false)
                }

            } else { // Turning Announce OFF
                // If announce is off, celebration should also be off
                updatedSettings = updatedSettings.copy(
                    isCelebrateEnabled = false, // General celebration
                    isDiceCritCelebrationEnabled = false // Dice crit celebration
                )
                // For coin flip, if announce is off, coin announcement should also be off
                if (settings.mode == RandomizerMode.COIN_FLIP) {
                    updatedSettings = updatedSettings.copy(isCoinAnnouncementEnabled = false)
                }
            }
            updatedSettings
        }
    }

    fun updateIsCelebrateEnabled(enabled: Boolean) { // General celebration (for Spin)
        updateSettingsField { settings ->
            val announceIsOn = settings.isAnnounceEnabled && settings.mode == RandomizerMode.SPIN
            settings.copy(isCelebrateEnabled = enabled && announceIsOn)
        }
    }

    fun updateIsSpinEnabled(enabled: Boolean) {
        updateSettingsField { it.copy(isSpinEnabled = enabled) }
    }

    // In RandomizerSettingsViewModel.kt
    fun updateSpinSequenceEnabled(isEnabled: Boolean) {
        val current = _settings.value ?: return // _settings is MutableLiveData
        var newAnnounceEnabled = current.isAnnounceEnabled
        var newConfettiEnabled = current.isConfettiEnabled

        if (isEnabled) { // If sequence is being turned ON
            newAnnounceEnabled = false // Turn off announcement
            newConfettiEnabled = false // Turn off confetti
        }
        // else: if sequence is being turned OFF, announce/confetti retain previous valid states
        // or re-evaluate based on other rules.

        _settings.value = current.copy(
            isSequenceEnabled = isEnabled,
            isAnnounceEnabled = newAnnounceEnabled,
            isConfettiEnabled = newConfettiEnabled
            // ... other logic ...
        )
    }
    fun updateDicePoolConfig(newConfigJson: String) {
        updateSettingsField { it.copy(dicePoolConfigJson = newConfigJson) }
    }

    fun updateDiceColorConfig(newConfigJson: String) {
        updateSettingsField { it.copy(diceColorConfigJson = newConfigJson) }
    }

    fun updateDiceModifierConfig(newConfigJson: String) {
        updateSettingsField { it.copy(diceModifierConfigJson = newConfigJson) }
    }

    fun updateGraphDistributionType(newType: GraphDistributionType) {
        updateSettingsField { currentState ->
            if (newType != GraphDistributionType.OFF) { // Turning Graph ON (for Dice)
                currentState.copy(
                    graphDistributionType = newType,
                    isAnnounceEnabled = false,
                    isDiceCritCelebrationEnabled = false
                )
            } else {
                currentState.copy(graphDistributionType = GraphDistributionType.OFF)
            }
        }
    }

    // --- Coin Flip Specific Settings Update Functions ---
    fun updateCoinColor(newColor: Int) {
        updateSettingsField { it.copy(coinColor = newColor) }
    }

    fun updateIsFlipAnimationEnabled(enabled: Boolean) {
        updateSettingsField { it.copy(isFlipAnimationEnabled = enabled) }
    }

    fun updateIsCoinFreeFormEnabled(enabled: Boolean) {
        updateSettingsField { settings ->
            var updatedSettings = settings.copy(isCoinFreeFormEnabled = enabled)
            if (enabled) { // Turning FreeForm ON
                // Mutually exclusive: Turn off Announcement and Probability
                updatedSettings = updatedSettings.copy(
                    isCoinAnnouncementEnabled = false,
                    coinProbabilityMode = CoinProbabilityMode.NONE.name
                )
            }
            updatedSettings
        }
    }

    fun updateIsCoinAnnouncementEnabled(enabled: Boolean) {
        updateSettingsField { settings ->
            var updatedSettings = settings.copy(isCoinAnnouncementEnabled = enabled)
            if (enabled) { // Turning Coin Announcement ON
                // Mutually exclusive: Turn off Probability and Free Form
                updatedSettings = updatedSettings.copy(
                    coinProbabilityMode = CoinProbabilityMode.NONE.name,
                    isCoinFreeFormEnabled = false
                )
            }
            updatedSettings
        }
    }

    fun updateCoinProbabilityMode(newMode: CoinProbabilityMode) {
        updateSettingsField { settings ->
            var updatedSettings = settings.copy(coinProbabilityMode = newMode.name)
            if (newMode != CoinProbabilityMode.NONE) { // Turning Probability ON (any mode other than None)
                // Mutually exclusive: Turn off Announcement and Free Form
                updatedSettings = updatedSettings.copy(
                    isCoinAnnouncementEnabled = false,
                    isCoinFreeFormEnabled = false
                )
                // If Graph Distribution is NOT selected, reset graph-specific sub-settings
                if (newMode != CoinProbabilityMode.GRAPH_DISTRIBUTION) {
                    updatedSettings = updatedSettings.copy(
                        coinGraphDistributionType = GraphDistributionType.OFF.name,
                        coinGraphPlotType = GraphPlotType.HISTOGRAM.name, // Reset to default
                        coinGraphFlipCount = 1000 // Reset to default
                    )
                }
            }
            updatedSettings
        }
    }

    fun updateCoinGraphDistributionType(newType: GraphDistributionType) {
        updateSettingsField { it.copy(coinGraphDistributionType = newType.name) }
    }

    fun updateCoinGraphLineStyle(newStyle: GraphPlotType) {
        updateSettingsField { it.copy(coinGraphPlotType = newStyle.name) }
    }

    fun updateCoinGraphFlipCount(count: Int) {
        // Add validation if needed (e.g., count > 0)
        updateSettingsField { it.copy(coinGraphFlipCount = count) }
    }

    private fun saveSettings(settingsToSave: SpinSettingsEntity) {
        if (instanceId == null) {
            Log.e(TAG, "Instance ID is null during saveSettings. This should not happen.")
            _errorEvent.postValue(Event(R.string.error_settings_instance_id_failed))
            return
        }

        val finalSettingsToSave = settingsToSave.copy(instanceId = this.instanceId)
        _settings.value = finalSettingsToSave // Update LiveData immediately

        viewModelScope.launch(Dispatchers.IO) {
            try {
                randomizerDao.saveSettings(finalSettingsToSave)
                Log.d(TAG, "Settings saved for instance ${finalSettingsToSave.instanceId}: Mode=${finalSettingsToSave.mode}, CoinProb=${finalSettingsToSave.coinProbabilityMode}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save settings for instance ${finalSettingsToSave.instanceId}", e)
                withContext(Dispatchers.Main) {
                    _errorEvent.value = Event(R.string.error_settings_save_failed_kitty)
                }
            }
        }
    }

    fun clearErrorEvent() {
        _errorEvent.value = Event(0) // Using 0 or a specific "no_error" resource ID
    }
}