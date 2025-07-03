package com.example.purramid.thepurramid.probabilities.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.gson.Gson

// Data class for a single coin type
enum class CoinType { B1, B5, B10, B25, MB1, MB2 }
data class CoinConfig(
    val type: CoinType,
    var quantity: Int = 1,
    var color: Int = 0xFFFFFF
)

data class CoinFlipSettings(
    val coinConfigs: List<CoinConfig> = CoinType.values().map { CoinConfig(it) },
    val flipAnimation: Boolean = true,
    val freeForm: Boolean = false,
    val announce: Boolean = true,
    val probabilityEnabled: Boolean = false,
    val probabilityType: String = "two_column",
    val graphEnabled: Boolean = false,
    val graphType: String = "histogram",
    val graphDistribution: String = "normal"
)

data class CoinFlipResult(
    val results: Map<CoinType, List<Boolean>> // true = heads, false = tails
)

class CoinFlipViewModel : ViewModel() {
    private val _settings = MutableLiveData(CoinFlipSettings())
    val settings: LiveData<CoinFlipSettings> = _settings

    private val _result = MutableLiveData<CoinFlipResult?>()
    val result: LiveData<CoinFlipResult?> = _result

    private var instanceId: Int = 0

    fun loadSettings(context: Context, instanceId: Int) {
        this.instanceId = instanceId
        val prefs = context.getSharedPreferences("probabilities_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("probabilities_coin_settings_$instanceId", null)
        if (json != null) {
            val loaded = Gson().fromJson(json, CoinFlipSettings::class.java)
            _settings.value = loaded
        }
    }

    private fun saveSettings(context: Context) {
        val prefs = context.getSharedPreferences("probabilities_prefs", Context.MODE_PRIVATE)
        val json = Gson().toJson(_settings.value)
        prefs.edit().putString("probabilities_coin_settings_$instanceId", json).apply()
    }

    fun updateCoinConfig(context: Context, type: CoinType, quantity: Int? = null, color: Int? = null) {
        val current = _settings.value ?: CoinFlipSettings()
        val updated = current.coinConfigs.map {
            if (it.type == type) it.copy(
                quantity = quantity ?: it.quantity,
                color = color ?: it.color
            ) else it
        }
        _settings.value = current.copy(coinConfigs = updated)
        saveSettings(context)
    }

    fun updateSettings(context: Context,
        flipAnimation: Boolean? = null,
        freeForm: Boolean? = null,
        announce: Boolean? = null,
        probabilityEnabled: Boolean? = null,
        probabilityType: String? = null,
        graphEnabled: Boolean? = null,
        graphType: String? = null,
        graphDistribution: String? = null
    ) {
        val current = _settings.value ?: CoinFlipSettings()
        _settings.value = current.copy(
            flipAnimation = flipAnimation ?: current.flipAnimation,
            freeForm = freeForm ?: current.freeForm,
            announce = announce ?: current.announce,
            probabilityEnabled = probabilityEnabled ?: current.probabilityEnabled,
            probabilityType = probabilityType ?: current.probabilityType,
            graphEnabled = graphEnabled ?: current.graphEnabled,
            graphType = graphType ?: current.graphType,
            graphDistribution = graphDistribution ?: current.graphDistribution
        )
        saveSettings(context)
    }

    fun flipCoins() {
        val current = _settings.value ?: CoinFlipSettings()
        val results = mutableMapOf<CoinType, List<Boolean>>()
        for (config in current.coinConfigs) {
            val flips = mutableListOf<Boolean>()
            for (i in 1..config.quantity) {
                flips.add(listOf(true, false).random())
            }
            results[config.type] = flips
        }
        _result.value = CoinFlipResult(results)
    }

    fun reset() {
        _result.value = null
    }

    // TODO: Persist/restore settings per instance
} 