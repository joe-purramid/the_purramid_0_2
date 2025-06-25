// File: main/kotlin/com/example/purramid/thepurramid/randomizers/viewmodel/DiceViewModel.kt
package com.example.purramid.thepurramid.randomizers.viewmodel

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.data.db.DEFAULT_DICE_POOL_JSON
import com.example.purramid.thepurramid.data.db.DEFAULT_EMPTY_JSON_MAP
import com.example.purramid.thepurramid.data.db.RandomizerDao
import com.example.purramid.thepurramid.data.db.SpinSettingsEntity
import com.example.purramid.thepurramid.randomizers.DiceSumResultType
import com.example.purramid.thepurramid.randomizers.GraphDistributionType
import com.example.purramid.thepurramid.randomizers.GraphPlotType // Ensure this enum is correctly defined and accessible
import com.example.purramid.thepurramid.randomizers.ui.DicePoolDialogFragment // For D10_KEYS etc.
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.random.Random

// Data structures based on your original DiceViewModel and refined needs
typealias RawRollsMap = Map<Int, List<Int>> // Key: Sides, Value: List of raw rolls for that type

data class PercentileRollDetail( // From your original DiceViewModel.kt
    val tensDieRoll: Int,       // Raw face value of the tens die (0-9)
    val unitsDieRoll: Int,      // Raw face value of the units die (0-9)
    val rawSum: Int,            // Result before modifier (1-100, where 00+0 is 100)
    val modifierApplied: Int,
    val finalValue: Int         // Result after modifier
)

// This data class holds all necessary information for UI display and announcements for a single roll event
data class ProcessedDiceResult(
    val standardDiceRolls: Map<Int, List<Int>>, // Raw rolls for each standard die type (Sides -> List<Roll>)
    val percentileResults: List<PercentileRollDetail>, // Details for each percentile set rolled
    val individualModifiedRolls: Map<Int, List<Int>>, // Modified rolls for each standard die type
    val sumPerTypeWithModifier: Map<Int, Int>, // Sum for each standard die type, after modifier
    val totalSumWithModifiers: Int, // Total sum of standard dice, after modifiers
    val sumResultType: DiceSumResultType, // How sums are displayed
    val isPercentileRollEvent: Boolean, // True if percentile dice were the primary focus or part of the roll
    val announcementString: String,
    val d20CritsRolled: Int,
    val isError: Boolean = false,
    val errorMessage: String? = null
)

// For Graphing
data class GraphDataPoint(val value: Float, val frequency: Float, val label: String = value.toInt().toString())

sealed class DiceGraphDisplayData {
    data class ChartData(
        val dataSetLabel: String,
        val points: List<GraphDataPoint>,
        val plotType: GraphPlotType,
        val xAxisLabels: List<String>? = null // For IndexAxisValueFormatter for Histogram
    ) : DiceGraphDisplayData()
    object Empty : DiceGraphDisplayData()
    object NotApplicable : DiceGraphDisplayData()
}

@HiltViewModel
class DiceViewModel @Inject constructor(
    application: Application,
    private val randomizerDao: RandomizerDao,
    sharedPreferences: SharedPreferences,
    savedStateHandle: SavedStateHandle
) : RandomizerViewModel(application, randomizerDao, sharedPreferences, savedStateHandle) {

    companion object {
        private const val TAG = "DiceViewModel"
        const val PERCENTILE_DIE_KEY = DicePoolDialogFragment.PERCENTILE_DIE_TYPE_KEY // Use defined constant
        const val D10_SIDES_FOR_MODIFIER = 10 // d10 modifier key (as in original logic)
        private const val SAVED_STATE_GRAPH_FACE_FREQUENCIES = "graphFaceFrequencies"
        private const val SAVED_STATE_GRAPH_ROLL_COUNT_HISTORY = "graphRollCountHistory"
    }

    private val gson = Gson()

    private val _processedDiceResult = MutableLiveData<ProcessedDiceResult?>()
    val processedDiceResult: LiveData<ProcessedDiceResult?> = _processedDiceResult

    private val _diceGraphData = MutableLiveData<DiceGraphDisplayData>(DiceGraphDisplayData.Empty)
    val diceGraphData: LiveData<DiceGraphDisplayData> = _diceGraphData

    private var faceValueFrequencies: MutableMap<Int, Int> = mutableMapOf() // Key: Face Value (1-N), Value: Frequency
    private var graphEventsCount: Int = 0

    init {
        restoreGraphDataFromSavedState()
        settings.observeForever { currentSettings ->
            currentSettings?.let {
                if (!it.isDiceGraphEnabled) {
                    _diceGraphData.value = DiceGraphDisplayData.Empty
                } else {
                    regenerateGraphDisplayData(it)
                }
                // If settings change that affect how results are processed (e.g., sum type, modifiers implicitly via config changes)
                // you might want to re-process the last roll if its raw components are stored, or just clear it.
                // For now, focusing on graph update.
            }
        }
    }

    private fun restoreGraphDataFromSavedState() {
        savedStateHandle.get<String>(SAVED_STATE_GRAPH_FACE_FREQUENCIES)?.let { json ->
            try {
                faceValueFrequencies = gson.fromJson(json, object : TypeToken<MutableMap<Int, Int>>() {}.type) ?: mutableMapOf()
            } catch (e: Exception) { Log.e(TAG, "Error restoring faceValueFrequencies", e) }
        }
        graphEventsCount = savedStateHandle.get<Int>(SAVED_STATE_GRAPH_ROLL_COUNT_HISTORY) ?: 0
    }

    private fun saveGraphDataToSavedState() {
        try {
            savedStateHandle[SAVED_STATE_GRAPH_FACE_FREQUENCIES] = gson.toJson(faceValueFrequencies)
            savedStateHandle[SAVED_STATE_GRAPH_ROLL_COUNT_HISTORY] = graphEventsCount
        } catch (e: Exception) { Log.e(TAG, "Error saving graph data", e) }
    }

    fun rollDice() {
        val currentSettings = settings.value
        if (currentSettings == null) {
            _processedDiceResult.value = ProcessedDiceResult(
                standardDiceRolls = emptyMap(), percentileResults = emptyList(),
                individualModifiedRolls = emptyMap(), sumPerTypeWithModifier = emptyMap(),
                totalSumWithModifiers = 0, sumResultType = DiceSumResultType.INDIVIDUAL, // Default
                isPercentileRollEvent = false, announcementString = "Error: Settings not loaded.",
                d20CritsRolled = 0, isError = true, errorMessage = "Settings not loaded."
            )
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            val poolConfig = parseDicePoolConfig(currentSettings.dicePoolConfigJson) ?: emptyMap()
            val modifiers = parseDiceModifierConfig(currentSettings.diceModifierConfigJson) ?: emptyMap()

            val currentRawStandardRolls = mutableMapOf<Int, MutableList<Int>>() // Sides -> List<RawRoll>
            val currentIndividualModifiedRolls = mutableMapOf<Int, MutableList<Int>>() // Sides -> List<ModifiedRoll>
            val currentSumPerTypeWithModifier = mutableMapOf<Int, Int>() // Sides -> SumWithMod
            var currentTotalSumWithModifiers = 0
            var d20CritCount = 0
            val rolledPercentileDetails = mutableListOf<PercentileRollDetail>()

            val allAggregatedFaceValuesForGraph = mutableListOf<Int>() // For graph accumulation

            var isThisAPercentileEvent = currentSettings.isPercentileDiceEnabled &&
                    (poolConfig[PERCENTILE_DIE_KEY] ?: 0) > 0

            // Handle Percentile Dice if present and enabled
            if (isThisAPercentileEvent) {
                val numPercentileSets = poolConfig[PERCENTILE_DIE_KEY] ?: 0
                // Modifier for percentile dice typically comes from d10 modifier in config
                val percentileModifier = modifiers[D10_SIDES_FOR_MODIFIER] ?: 0

                repeat(numPercentileSets) {
                    val tensDieRawRoll = Random.nextInt(0, 10) // 0-9
                    val unitsDieRawRoll = Random.nextInt(0, 10) // 0-9

                    val tensDisplayValue = tensDieRawRoll * 10 // e.g. 00, 10, ..., 90
                    // Standard d% result: 00 (tens) + 0 (units) = 100. Otherwise, tens + units.
                    val rawPercentileSum = if (tensDisplayValue == 0 && unitsDieRawRoll == 0) 100 else tensDisplayValue + unitsDieRawRoll
                    val finalPercentileValue = rawPercentileSum + percentileModifier

                    rolledPercentileDetails.add(
                        PercentileRollDetail(
                            tensDieRoll = tensDieRawRoll, // Store raw 0-9
                            unitsDieRoll = unitsDieRawRoll, // Store raw 0-9
                            rawSum = rawPercentileSum,
                            modifierApplied = percentileModifier,
                            finalValue = finalPercentileValue
                        )
                    )
                    // For graphing individual face values 1-N, how should percentile results be treated?
                    // User rule: "X-axis ... 1 to max die face". Percentile is 1-100.
                    // TODO: Consider x-axis increments for percentile dice. (User TODO from prompt)
                    // For now, we'll add final percentile value if it fits standard face value range,
                    // or we need a separate mechanism/graph for percentiles.
                    // Based on "1 to N", we will graph it if N >= 100.
                    allAggregatedFaceValuesForGraph.add(finalPercentileValue)
                }
            }

            // Handle Standard Dice
            poolConfig.forEach { (sides, count) ->
                if (sides == PERCENTILE_DIE_KEY || sides <= 0 || count <= 0) return@forEach

                val dieModifier = modifiers[sides] ?: 0
                val rollsForThisType = mutableListOf<Int>()
                val modifiedRollsForThisType = mutableListOf<Int>()
                var sumForThisTypeRaw = 0

                repeat(count) {
                    val rawRoll = Random.nextInt(1, sides + 1)
                    rollsForThisType.add(rawRoll)
                    allAggregatedFaceValuesForGraph.add(rawRoll) // Add raw face for graphing

                    modifiedRollsForThisType.add(rawRoll + dieModifier)
                    sumForThisTypeRaw += rawRoll
                    if (sides == 20 && rawRoll == 20) {
                        d20CritCount++
                    }
                }
                currentRawStandardRolls[sides] = rollsForThisType
                currentIndividualModifiedRolls[sides] = modifiedRollsForThisType
                val sumForThisTypeWithMod = sumForThisTypeRaw + (count * dieModifier) // Modifier applied to each die
                currentSumPerTypeWithModifier[sides] = sumForThisTypeWithMod
                currentTotalSumWithModifiers += sumForThisTypeWithMod
            }

            if (currentSettings.isDiceGraphEnabled) {
                updateGraphAccumulation(allAggregatedFaceValuesForGraph, currentSettings)
            }

            val announcement = formatAnnouncementString(
                currentRawStandardRolls,
                rolledPercentileDetails,
                isThisAPercentileEvent,
                modifiers,
                currentSettings.diceSumResultType,
                currentSumPerTypeWithModifier,
                currentTotalSumWithModifiers
            )

            val finalResult = ProcessedDiceResult(
                standardDiceRolls = currentRawStandardRolls,
                percentileResults = rolledPercentileDetails,
                individualModifiedRolls = currentIndividualModifiedRolls,
                sumPerTypeWithModifier = currentSumPerTypeWithModifier,
                totalSumWithModifiers = currentTotalSumWithModifiers,
                sumResultType = currentSettings.diceSumResultType,
                isPercentileRollEvent = isThisAPercentileEvent,
                announcementString = announcement,
                d20CritsRolled = d20CritCount
            )

            withContext(Dispatchers.Main) {
                _processedDiceResult.value = finalResult
            }
        }
    }

    // This formatting logic is based on your original DiceViewModel's comprehensive approach
    private fun formatAnnouncementString(
        standardRolls: RawRollsMap,
        percentileDetails: List<PercentileRollDetail>,
        isPercentileEventContext: Boolean,
        modifiers: Map<Int, Int>,
        sumType: DiceSumResultType,
        sumPerTypeWithMod: Map<Int, Int>,
        totalSumWithMods: Int
    ): String {
        val announcements = mutableListOf<String>()

        if (percentileDetails.isNotEmpty()) {
            percentileDetails.forEach { p ->
                val tensDisplay = p.tensDieRoll * 10
                val modStr = if (p.modifierApplied != 0) " (${if (p.modifierApplied > 0) "+" else ""}${p.modifierApplied})" else ""
                announcements.add("d%: $tensDisplay (d00) + ${p.unitsDieRoll} (d10)$modStr = ${p.finalValue}%")
            }
        }

        if (standardRolls.isNotEmpty()) {
            if (percentileDetails.isNotEmpty()) announcements.add("---") // Separator

            when (sumType) {
                DiceSumResultType.INDIVIDUAL -> {
                    standardRolls.toSortedMap().forEach { (sides, rolls) ->
                        val mod = modifiers[sides] ?: 0
                        val rollsStr = rolls.joinToString(", ") { r ->
                            "${r + mod}" + (if (mod != 0) "($r${if (mod > 0) "+" else ""}$mod)" else "")
                        }
                        announcements.add("${rolls.size}d$sides: $rollsStr")
                    }
                }
                DiceSumResultType.SUM_TYPE -> {
                    sumPerTypeWithMod.toSortedMap().forEach { (sides, sumVal) ->
                        // Need count for label
                        val count = standardRolls[sides]?.size ?: 0
                        if (count > 0) announcements.add("${count}d$sides Sum: $sumVal")
                    }
                }
                DiceSumResultType.SUM_TOTAL -> {
                    announcements.add("Total Sum (Standard Dice): $totalSumWithMods")
                }
            }
        }
        return if (announcements.isEmpty()) "No dice rolled or pool empty." else announcements.joinToString("\n")
    }

    private fun updateGraphAccumulation(rolledFaceValues: List<Int>, settings: SpinSettingsEntity) {
        val poolConfig = parseDicePoolConfig(settings.dicePoolConfigJson) ?: emptyMap()
        // Max face from standard dice, percentile results (1-100) might extend this if graphed directly
        val maxStandardDieFace = poolConfig.keys.filter { it > 0 }.maxOrNull() ?: 20

        rolledFaceValues.forEach { faceValue ->
            // User rule: X-axis for dice is 1 to max die face in pool (e.g. 20 for d20).
            // If percentile results (1-100) are included in `rolledFaceValues` for graphing,
            // this condition decides if they get added to `faceValueFrequencies`.
            // This implies percentile graph might be separate or X-axis rule needs specific handling for it.
            // For now, only counting if it falls within standard die face range (1-N) or if N is extended for percentile.
            // Assuming graph X-axis is primarily for standard die faces 1 to `maxStandardDieFace`.
            if (faceValue in 1..maxStandardDieFace) { // Only graph values that fit the "1 to max die face" x-axis
                faceValueFrequencies[faceValue] = (faceValueFrequencies[faceValue] ?: 0) + 1
            } else if (faceValue in 1..100 && poolConfig.containsKey(PERCENTILE_DIE_KEY)) {
                // If percentile was rolled, and we decide to graph its results (1-100)
                // This assumes the graph's X-axis will dynamically extend or percentile has its own graph.
                // For now, this means percentile graph data is collected IF a d100 was effectively rolled.
                // And the X-axis in regenerate will need to be aware.
                // TODO: Clarify how percentile results (1-100) are plotted alongside standard dice (1-N) on X-axis.
                // For now, let's assume they are added to faceValueFrequencies if the maxDieFaceInPool can go up to 100.
                faceValueFrequencies[faceValue] = (faceValueFrequencies[faceValue] ?: 0) + 1
            }
        }
        graphEventsCount++
        regenerateGraphDisplayData(settings)
        saveGraphDataToSavedState()
    }

    private fun regenerateGraphDisplayData(settings: SpinSettingsEntity) {
        if (!settings.isDiceGraphEnabled) {
            _diceGraphData.value = DiceGraphDisplayData.Empty
            return
        }
        if (faceValueFrequencies.isEmpty() && graphEventsCount > 0) {
            _diceGraphData.value = DiceGraphDisplayData.Empty
            return
        }
        if (faceValueFrequencies.isEmpty() && graphEventsCount == 0) {
            _diceGraphData.value = DiceGraphDisplayData.Empty
            return
        }

        val plotType = try { GraphPlotType.valueOf(settings.diceGraphPlotType) }
        catch (e: Exception) { GraphPlotType.HISTOGRAM }

        val poolConfig = parseDicePoolConfig(settings.dicePoolConfigJson) ?: emptyMap()
        var maxFaceForXAxis = poolConfig.keys.filter { it > 0 }.maxOrNull() ?: 6 // Max from standard dice

        // If percentile dice are in the pool AND we are graphing them on the same axis, extend maxFace.
        if (poolConfig.containsKey(PERCENTILE_DIE_KEY)) {
            // Check if any percentile results are in faceValueFrequencies keys
            val maxPercentileValueInGraph = faceValueFrequencies.keys.filter { it > maxFaceForXAxis }.maxOrNull()
            if (maxPercentileValueInGraph != null) {
                maxFaceForXAxis = maxFaceForXAxis.coerceAtLeast(maxPercentileValueInGraph)
            } else if (isPercentileDiceInPool(poolConfig)) { // If percentile dice are configured, ensure X-axis can show up to 100 if needed
                maxFaceForXAxis = maxFaceForXAxis.coerceAtLeast(100)
            }
        }


        val points = mutableListOf<GraphDataPoint>()
        val xAxisLabelsForHistogram = mutableListOf<String>()

        for (faceValue in 1..maxFaceForXAxis) {
            val frequency = faceValueFrequencies[faceValue] ?: 0
            if (plotType == GraphPlotType.LINE_GRAPH) {
                // For Line chart, only add point if frequency > 0 or if you want to show all points on X axis
                // To make line continuous over all possible face values:
                points.add(GraphDataPoint(value = faceValue.toFloat(), frequency = frequency.toFloat(), label = faceValue.toString()))
            } else { // HISTOGRAM or QQ (fallback to HISTOGRAM)
                // X-value is index for histogram
                points.add(GraphDataPoint(value = (faceValue - 1).toFloat(), frequency = frequency.toFloat(), label = faceValue.toString()))
                xAxisLabelsForHistogram.add(faceValue.toString())
            }
        }

        // User TODOs from prompt:
        // TODO: Consider tracking die types individually. This would require using different bar/line/dot types and/or colors...
        // TODO: Consider x-axis increments for percentile dice. 1-100 is too many increments for a graph...
        // TODO: Consider responsive axis labeling so that the larger the window size, the more granular the axis increments can be.

        _diceGraphData.value = DiceGraphDisplayData.ChartData(
            dataSetLabel = "Dice Face Frequency", // Use String Resource
            points = points,
            plotType = plotType,
            xAxisLabels = if (plotType == GraphPlotType.HISTOGRAM) xAxisLabelsForHistogram else null
        )
    }

    private fun isPercentileDiceInPool(poolConfig: Map<Int, Int>): Boolean {
        return (poolConfig[PERCENTILE_DIE_KEY] ?: 0) > 0
    }

    fun resetGraphData() {
        faceValueFrequencies.clear()
        graphEventsCount = 0
        _diceGraphData.value = DiceGraphDisplayData.Empty
        saveGraphDataToSavedState()
    }

    fun parseDicePoolConfig(json: String?): Map<Int, Int>? { // Make public if fragment needs it
        if (json.isNullOrEmpty()) return parseDicePoolConfig(DEFAULT_DICE_POOL_JSON)
        return try {
            gson.fromJson(json, object : TypeToken<Map<Int, Int>>() {}.type)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing dice pool config: $json", e)
            parseDicePoolConfig(DEFAULT_DICE_POOL_JSON)
        }
    }

    private fun parseDiceModifierConfig(json: String?): Map<Int, Int>? {
        if (json.isNullOrEmpty() || json == DEFAULT_EMPTY_JSON_MAP) return emptyMap()
        return try {
            gson.fromJson(json, object : TypeToken<Map<Int, Int>>() {}.type)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing dice modifier config: $json", e)
            emptyMap()
        }
    }

    override fun onCleared() {
        Log.d(TAG, "ViewModel instance $instanceId cleared.")
        saveGraphDataToSavedState() // Ensure data is saved
        super.onCleared()
    }
}