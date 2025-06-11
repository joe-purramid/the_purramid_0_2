// SpinSettingsEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.purramid.thepurramid.randomizers.RandomizerMode
import com.example.purramid.thepurramid.randomizers.SlotsColumnState
import com.example.purramid.thepurramid.randomizers.DiceSumResultType
import com.example.purramid.thepurramid.randomizers.GraphDistributionType
import com.example.purramid.thepurramid.randomizers.GraphPlotType
import com.example.purramid.thepurramid.randomizers.CoinProbabilityMode
import java.util.UUID

// Default dice pool: 1d6
const val DEFAULT_DICE_POOL_JSON = "{\"6\":1}"
// Default empty JSON map for colors and modifiers
const val DEFAULT_EMPTY_JSON_MAP = "{}"
// Default color for Coin Flip (Goldenrod)
const val DEFAULT_COIN_COLOR_INT = 0xFFDAA520.toInt()

@Entity(tableName = "spin_settings")
data class SpinSettingsEntity(
    @PrimaryKey val instanceId: UUID,

    var mode: RandomizerMode = RandomizerMode.SPIN,

    // --- Multi-mode settings ---
    var currentListId: UUID? = null, // ID of the currently selected list
    var isAnnounceEnabled: Boolean = false,
    var isCelebrateEnabled: Boolean = false,

    // --- Spin Specific ---
    var isSpinEnabled: Boolean = true,
    var isSequenceEnabled: Boolean = false,
    val currentSpinListId: Long? = null, // Assuming Long is the type of your List ID

    // --- Slots Specific ---
    val numSlotsColumns: Int = 3, // Default to 3 columns
    var slotsColumnStates: List<SlotsColumnState> = emptyList(), // List to hold state for each column
    val currentSlotsListId: Long? = null,

    // --- Dice Specific ---
    // Configs stored as JSON strings, handled by TypeConverters
    var dicePoolConfigJson: String = DEFAULT_DICE_POOL_JSON, // Map<Int, Int> sides -> count
    var diceColorConfigJson: String = DEFAULT_EMPTY_JSON_MAP, // Map<Int, Int> sides -> colorInt
    var diceModifierConfigJson: String = DEFAULT_EMPTY_JSON_MAP, // Map<Int, Int> sides -> modifier
    var useDicePips: Boolean = false, // Default numbers for d6
    var isPercentileDiceEnabled: Boolean = false, // Default off
    var isDiceAnimationEnabled: Boolean = true, // Default on
    var isDiceCritCelebrationEnabled: Boolean = false, // Default off
    var diceSumResultType: DiceSumResultType = DiceSumResultType.INDIVIDUAL, // Default
    var graphDistributionType: GraphDistributionType = GraphDistributionType.OFF, // Default off
    var diceGraphPlotType: String = GraphPlotType.HISTOGRAM.name, // Default solid
    var graphRollCount: Int = 1000, // Default for Normal/Uniform

    // --- Coin Flip Mode Specific ---
    var coinColor: Int = DEFAULT_COIN_COLOR_INT,
    var isFlipAnimationEnabled: Boolean = true,
    var isCoinFreeFormEnabled: Boolean = false,
    var isCoinAnnouncementEnabled: Boolean = true,
    var coinProbabilityMode: String = CoinProbabilityMode.NONE.name, // Store enum as string
    var coinGraphDistributionType: String = GraphDistributionType.OFF.name,
    var coinGraphPlotType: String = GraphPlotType.HISTOGRAM.name,
    var coinGraphFlipCount: Int = 1000,
)