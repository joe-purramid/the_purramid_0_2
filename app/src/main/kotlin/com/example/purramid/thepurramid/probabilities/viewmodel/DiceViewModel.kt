package com.example.purramid.thepurramid.probabilities.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.purramid.thepurramid.probabilities.ProbabilitiesMode
import com.google.gson.Gson

// Data class for a single die type
enum class DieType { D4, D6, D8, D10, D12, D20, PERCENTILE }
data class DieConfig(
    val type: DieType,
    var quantity: Int = 1,
    var color: Int = 0xFFFFFF,
    var modifier: Int = 0,
    var usePips: Boolean = false
)

data class DiceSettings(
    val dieConfigs: List<DieConfig> = DieType.values().map { DieConfig(it) },
    val usePercentile: Boolean = false,
    val rollAnimation: Boolean = true,
    val critEnabled: Boolean = false,
    val graphEnabled: Boolean = false,
    val graphType: String = "histogram",
    val graphDistribution: String = "normal"
)

data class DiceResult(
    val results: Map<DieType, List<Int>>,
    val sum: Int,
    val crits: List<Boolean>
)

class DiceViewModel : ViewModel() {
    private val _settings = MutableLiveData(DiceSettings())
    val settings: LiveData<DiceSettings> = _settings

    private val _result = MutableLiveData<DiceResult?>()
    val result: LiveData<DiceResult?> = _result

    private var instanceId: Int = 0

    fun loadSettings(context: Context, instanceId: Int) {
        this.instanceId = instanceId
        val prefs = context.getSharedPreferences("probabilities_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("probabilities_dice_settings_$instanceId", null)
        if (json != null) {
            val loaded = Gson().fromJson(json, DiceSettings::class.java)
            _settings.value = loaded
        }
    }

    private fun saveSettings(context: Context) {
        val prefs = context.getSharedPreferences("probabilities_prefs", Context.MODE_PRIVATE)
        val json = Gson().toJson(_settings.value)
        prefs.edit().putString("probabilities_dice_settings_$instanceId", json).apply()
    }

    fun updateDieConfig(context: Context, type: DieType, quantity: Int? = null, color: Int? = null, modifier: Int? = null, usePips: Boolean? = null) {
        val current = _settings.value ?: DiceSettings()
        val updated = current.dieConfigs.map {
            if (it.type == type) it.copy(
                quantity = quantity ?: it.quantity,
                color = color ?: it.color,
                modifier = modifier ?: it.modifier,
                usePips = usePips ?: it.usePips
            ) else it
        }
        _settings.value = current.copy(dieConfigs = updated)
        saveSettings(context)
    }

    fun updateSettings(context: Context,
        usePercentile: Boolean? = null,
        rollAnimation: Boolean? = null,
        critEnabled: Boolean? = null,
        graphEnabled: Boolean? = null,
        graphType: String? = null,
        graphDistribution: String? = null
    ) {
        val current = _settings.value ?: DiceSettings()
        _settings.value = current.copy(
            usePercentile = usePercentile ?: current.usePercentile,
            rollAnimation = rollAnimation ?: current.rollAnimation,
            critEnabled = critEnabled ?: current.critEnabled,
            graphEnabled = graphEnabled ?: current.graphEnabled,
            graphType = graphType ?: current.graphType,
            graphDistribution = graphDistribution ?: current.graphDistribution
        )
        saveSettings(context)
    }

    fun rollDice() {
        val current = _settings.value ?: DiceSettings()
        val results = mutableMapOf<DieType, List<Int>>()
        var sum = 0
        val crits = mutableListOf<Boolean>()
        for (config in current.dieConfigs) {
            val rolls = mutableListOf<Int>()
            val sides = when (config.type) {
                DieType.D4 -> 4
                DieType.D6 -> 6
                DieType.D8 -> 8
                DieType.D10 -> 10
                DieType.D12 -> 12
                DieType.D20 -> 20
                DieType.PERCENTILE -> 100
            }
            for (i in 1..config.quantity) {
                val roll = if (config.type == DieType.PERCENTILE) {
                    val tens = (1..10).random() * 10 % 100
                    val ones = (1..10).random() % 10
                    val value = if (tens == 0 && ones == 0) 100 else tens + ones
                    value
                } else {
                    (1..sides).random()
                }
                rolls.add(roll)
                sum += roll + config.modifier
                if (config.type == DieType.D20) crits.add(roll == 20)
            }
            results[config.type] = rolls
        }
        _result.value = DiceResult(results, sum, crits)
    }

    fun reset() {
        _result.value = null
    }

    // TODO: Persist/restore settings per instance
} 