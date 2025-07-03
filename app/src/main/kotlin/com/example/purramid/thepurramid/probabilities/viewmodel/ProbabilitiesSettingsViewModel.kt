package com.example.purramid.thepurramid.probabilities.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.purramid.thepurramid.probabilities.ProbabilitiesMode

class ProbabilitiesSettingsViewModel : ViewModel() {
    private val _settings = MutableLiveData<ProbabilitiesSettingsEntity?>()
    val settings: LiveData<ProbabilitiesSettingsEntity?> = _settings

    fun updateMode(newMode: ProbabilitiesMode) {
        val current = _settings.value ?: ProbabilitiesSettingsEntity(mode = ProbabilitiesMode.DICE, instanceId = 1)
        _settings.value = current.copy(mode = newMode)
    }

    fun loadSettings(instanceId: Int) {
        // For now, just default to DICE
        _settings.value = ProbabilitiesSettingsEntity(mode = ProbabilitiesMode.DICE, instanceId = instanceId)
    }
}

data class ProbabilitiesSettingsEntity(
    val mode: ProbabilitiesMode,
    val instanceId: Int
) 