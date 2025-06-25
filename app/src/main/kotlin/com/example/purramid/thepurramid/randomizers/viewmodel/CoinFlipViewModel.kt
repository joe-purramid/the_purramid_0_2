// CoinFlipViewModel.kt
package com.example.purramid.thepurramid.randomizers.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.thepurramid.data.db.RandomizerDao
import com.example.purramid.thepurramid.data.db.SpinSettingsEntity
import com.example.purramid.thepurramid.randomizers.CoinProbabilityMode
import com.example.purramid.thepurramid.util.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

// --- Data Classes for Coin Flip ---

enum class CoinType(val value: Int, val label: String) {
    BIT_1(1, "1b"),
    BIT_5(5, "5b"),
    BIT_10(10, "10b"),
    BIT_25(25, "25b"),
    MEATBALL_1(100, "1MB"), // Assign arbitrary high values for sorting/internal distinction if needed
    MEATBALL_2(200, "2MB");

    companion object {
        fun fromLabel(label: String): CoinType? = values().find { it.label == label }
    }
}

enum class CoinFace {
    HEADS, // Obverse
    TAILS  // Reverse
}

data class CoinInPool(
    val id: UUID = UUID.randomUUID(), // Unique ID for this specific coin instance in the pool
    val type: CoinType,
    var currentFace: CoinFace = CoinFace.HEADS, // Default to heads
    // For FreeForm mode positioning if needed directly on the coin item:
    var xPos: Float = 0f,
    var yPos: Float = 0f
)

data class FlipResult(
    val coinResults: List<Pair<CoinType, CoinFace>>, // Could be CoinInPool if IDs are important for animation
    val totalHeads: Int,
    val totalTails: Int
)

// Represents a single cell in a probability grid (3x3, 6x6, 10x10)
data class ProbabilityGridCell(
    val rowIndex: Int,
    val colIndex: Int,
    var headsCount: Int = 0,
    var tailsCount: Int = 0,
    var isFilled: Boolean = false
) {
    fun getDisplayValue(): String {
        return if (!isFilled) "" else "${headsCount}H/${tailsCount}T"
    }
}

data class CoinFlipUiState(
    val settings: SpinSettingsEntity? = null, // Loaded from RandomizerSettingsViewModel/DB
    val coinPool: List<CoinInPool> = emptyList(),
    val lastFlipResult: FlipResult? = null,
    val isFlipping: Boolean = false, // For managing animation state in UI
    val freeFormButtonText: String = "Tails", // Or R.string.tails
    val probabilityGrid: List<ProbabilityGridCell> = emptyList(),
    val probabilityGridColumns: Int = 0, // For GridLayout span count
    val isProbabilityGridFull: Boolean = false,
    val errorEvent: Event<String>? = null,
    // TODO: Add fields for graph data if/when implemented
)

@HiltViewModel
class CoinFlipViewModel @Inject constructor(
    private val randomizerDao: RandomizerDao, // To load its own settings
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        const val KEY_INSTANCE_ID = "instanceId" // Should match RandomizerSettingsViewModel
        private const val TAG = "CoinFlipViewModel"
        const val MAX_COINS_PER_TYPE = 10
        const val FLIP_ANIMATION_DURATION_MS = 750L
    }

    private val instanceId: Int = savedStateHandle.get<Int>(KEY_INSTANCE_ID) ?: 0

    private val _uiState = MutableStateFlow(CoinFlipUiState())
    val uiState: StateFlow<CoinFlipUiState> = _uiState.asStateFlow()

    // To observe settings from the database for this specific instance
    // This ensures that if settings are changed elsewhere, this VM reacts.
    private val _settings = MutableLiveData<SpinSettingsEntity?>()
    val settings: LiveData<SpinSettingsEntity?> = _settings


    init {
        if (instanceId > 0) {
            loadSettings(instanceId) // Load settings initially

            // Observe settings changes from DB for this instance
            viewModelScope.launch {
                // This is a simplified observation. For production, you might use a Flow from DAO.
                // For now, we'll rely on re-loading settings when the fragment comes to foreground
                // or when settings fragment signals a change.
                // A more robust way is to have RandomizerSettingsViewModel expose a Flow of settings for the instanceId
                // and this VM collects it. Or RandomizerDao provides a Flow.
            }
        } else {
            _uiState.update { it.copy(errorEvent = Event("CoinFlipViewModel: Invalid Instance ID")) }
            Log.e(TAG, "Critical Error: Instance ID is invalid for CoinFlipViewModel: $instanceId")
        }

        // Initialize with a default coin pool if desired, or leave empty
        // addCoinToPool(CoinType.BIT_1) // Example: start with one 1-bit coin
    }

    private fun loadSettings(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loadedSettings = randomizerDao.getSettingsForInstance(id)
                withContext(Dispatchers.Main) {
                    _settings.value = loadedSettings // Update LiveData for external observation
                    _uiState.update { it.copy(settings = loadedSettings) }
                    Log.d(TAG, "Settings loaded for $id: $loadedSettings")
                    // Initialize probability grid if a grid mode is set
                    loadedSettings?.coinProbabilityMode?.let { modeName ->
                        val probMode = CoinProbabilityMode.valueOf(modeName)
                        initializeProbabilityGrid(probMode)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading settings for instance $id", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(errorEvent = Event("Failed to load settings.")) }
                }
            }
        }
    }

    // Called when settings might have changed (e.g., from settings fragment)
    fun refreshSettings() {
        if (instanceId > 0) {
            loadSettings(instanceId)
        }
    }

    // --- Coin Pool Management ---
    fun addCoinToPool(coinType: CoinType) {
        val currentPool = _uiState.value.coinPool.toMutableList()
        if (currentPool.count { it.type == coinType } < MAX_COINS_PER_TYPE) {
            currentPool.add(CoinInPool(type = coinType))
            _uiState.update { it.copy(coinPool = currentPool) }
        } else {
            // Optionally notify user that max for this coin type is reached
            _uiState.update { it.copy(errorEvent = Event("Max ${coinType.label} coins reached.")) }
        }
    }

    fun removeCoinFromPool(coinType: CoinType) {
        val currentPool = _uiState.value.coinPool.toMutableList()
        val coinToRemove = currentPool.findLast { it.type == coinType }
        coinToRemove?.let {
            currentPool.remove(it)
            _uiState.update { state -> state.copy(coinPool = currentPool) }
        }
    }

    fun clearCoinPool() {
        _uiState.update { it.copy(coinPool = emptyList()) }
    }

    // --- Flipping Logic ---
    fun flipCoins() {
        val currentSettings = _uiState.value.settings ?: return // Need settings
        val currentCoinPool = _uiState.value.coinPool
        if (currentCoinPool.isEmpty()) {
            _uiState.update { it.copy(errorEvent = Event("Add coins to flip!")) }
            return
        }

        _uiState.update { it.copy(isFlipping = true, lastFlipResult = null) }

        viewModelScope.launch {
            // Simulate animation delay if enabled
            if (currentSettings.isFlipAnimationEnabled) {
                delay(FLIP_ANIMATION_DURATION_MS)
            }

            val results = mutableListOf<Pair<CoinType, CoinFace>>()
            var heads = 0
            var tails = 0

            val updatedPool = currentCoinPool.map { coin ->
                val face = if (Random.nextBoolean()) CoinFace.HEADS else CoinFace.TAILS
                if (face == CoinFace.HEADS) heads++ else tails++
                results.add(Pair(coin.type, face))
                coin.copy(currentFace = face) // Update the face of the coin in the pool
            }

            val flipResult = FlipResult(results, heads, tails)
            _uiState.update {
                it.copy(
                    isFlipping = false,
                    lastFlipResult = flipResult,
                    coinPool = updatedPool // Persist the new faces in the displayed pool
                )
            }
            Log.d(TAG, "Flip Result: H:$heads, T:$tails")

            // Handle probability updates
            handleProbabilityUpdate(flipResult)
        }
    }

    // --- Free Form Mode Logic ---
    fun toggleFreeFormCoinFaces() {
        val currentPool = _uiState.value.coinPool
        if (currentPool.isEmpty()) return

        val newFace = if (_uiState.value.freeFormButtonText.equals("Tails", ignoreCase = true)) CoinFace.TAILS else CoinFace.HEADS
        val newButtonText = if (newFace == CoinFace.TAILS) "Heads" else "Tails"

        val updatedPool = currentPool.map { it.copy(currentFace = newFace) }
        _uiState.update { it.copy(coinPool = updatedPool, freeFormButtonText = newButtonText) }
    }

    fun updateCoinPositionInFreeForm(coinId: UUID, x: Float, y: Float) {
        val currentPool = _uiState.value.coinPool.toMutableList()
        val coinIndex = currentPool.indexOfFirst { it.id == coinId }
        if (coinIndex != -1) {
            currentPool[coinIndex] = currentPool[coinIndex].copy(xPos = x, yPos = y)
            _uiState.update { it.copy(coinPool = currentPool) }
            // Note: Persisting these positions would require DB changes or saving to settings.
            // For now, it's transient.
        }
    }


    // --- Probability Grid Logic ---
    private fun initializeProbabilityGrid(mode: CoinProbabilityMode) {
        val gridSize = when (mode) {
            CoinProbabilityMode.GRID_3X3 -> 3
            CoinProbabilityMode.GRID_6X6 -> 6
            CoinProbabilityMode.GRID_10X10 -> 10
            else -> 0
        }

        if (gridSize > 0) {
            val newGrid = mutableListOf<ProbabilityGridCell>()
            for (r in 0 until gridSize) {
                for (c in 0 until gridSize) {
                    newGrid.add(ProbabilityGridCell(rowIndex = r, colIndex = c))
                }
            }
            _uiState.update { it.copy(probabilityGrid = newGrid, probabilityGridColumns = gridSize, isProbabilityGridFull = false) }
        } else {
            _uiState.update { it.copy(probabilityGrid = emptyList(), probabilityGridColumns = 0, isProbabilityGridFull = false) }
        }
    }

    private fun handleProbabilityUpdate(flipResult: FlipResult) {
        val settings = _uiState.value.settings ?: return
        val probMode = CoinProbabilityMode.valueOf(settings.coinProbabilityMode)

        when (probMode) {
            CoinProbabilityMode.GRID_3X3, CoinProbabilityMode.GRID_6X6, CoinProbabilityMode.GRID_10X10 -> {
                addResultToGrid(flipResult.totalHeads, flipResult.totalTails)
            }
            // CoinProbabilityMode.TWO_COLUMNS -> { /* UI Fragment will likely just display lastFlipResult.totalHeads/Tails */ }
            // CoinProbabilityMode.GRAPH_DISTRIBUTION -> { /* TODO: Update graph data */ }
            else -> { /* No specific probability update needed for NONE or others not yet implemented */ }
        }
    }

    private fun addResultToGrid(heads: Int, tails: Int) {
        val currentGrid = _uiState.value.probabilityGrid.toMutableList()
        val firstEmptyCellIndex = currentGrid.indexOfFirst { !it.isFilled }

        if (firstEmptyCellIndex != -1) {
            currentGrid[firstEmptyCellIndex] = currentGrid[firstEmptyCellIndex].copy(
                headsCount = heads,
                tailsCount = tails,
                isFilled = true
            )
            val isNowFull = currentGrid.all { it.isFilled }
            _uiState.update { it.copy(probabilityGrid = currentGrid, isProbabilityGridFull = isNowFull) }
        }
    }

    fun handleGridCellTap(tappedRow: Int, tappedCol: Int) {
        val currentGrid = _uiState.value.probabilityGrid
        val gridSize = _uiState.value.probabilityGridColumns
        if (gridSize == 0) return

        val tappedCellLinearIndex = tappedRow * gridSize + tappedCol
        if (tappedCellLinearIndex >= currentGrid.size || currentGrid[tappedCellLinearIndex].isFilled) {
            return // Tapped on a filled cell or out of bounds
        }

        // Find all empty cells up to and including the tapped one
        val cellsToFillIndices = mutableListOf<Int>()
        for (i in 0..tappedCellLinearIndex) {
            if (!currentGrid[i].isFilled) {
                cellsToFillIndices.add(i)
            }
        }

        if (cellsToFillIndices.isNotEmpty()) {
            viewModelScope.launch {
                // Set isFlipping true for UI feedback if desired (though flips are per cell here)
                // _uiState.update { it.copy(isFlipping = true) } // Optional general flipping state

                val updatedGrid = currentGrid.toMutableList()
                var anyCellFilledThisTurn = false

                for (cellIndex in cellsToFillIndices) {
                    // Perform a new coin flip for this cell
                    val tempPool = _uiState.value.coinPool // Use current pool for each "virtual" flip
                    if (tempPool.isEmpty()) break // Stop if pool becomes empty (should not happen if not modifiable during this)

                    var tempHeads = 0
                    var tempTails = 0
                    tempPool.forEach { _ -> // Simulate flipping each coin in the pool
                        if (Random.nextBoolean()) tempHeads++ else tempTails++
                    }

                    // Update the specific cell
                    updatedGrid[cellIndex] = updatedGrid[cellIndex].copy(
                        headsCount = tempHeads,
                        tailsCount = tempTails,
                        isFilled = true
                    )
                    anyCellFilledThisTurn = true

                    // Provide a small delay for visual effect if desired
                    if (_uiState.value.settings?.isFlipAnimationEnabled == true) {
                        delay(100) // Small delay between filling cells
                        _uiState.update { it.copy(probabilityGrid = updatedGrid.toList()) } // Update UI progressively
                    }
                }

                if (anyCellFilledThisTurn) {
                    val isNowFull = updatedGrid.all { it.isFilled }
                    _uiState.update { it.copy(probabilityGrid = updatedGrid.toList(), isProbabilityGridFull = isNowFull) }
                }
                // _uiState.update { it.copy(isFlipping = false) } // Reset general flipping state
            }
        }
    }


    fun resetProbabilityGrid() {
        _uiState.value.settings?.coinProbabilityMode?.let { modeName ->
            val probMode = CoinProbabilityMode.valueOf(modeName)
            initializeProbabilityGrid(probMode) // This also sets isProbabilityGridFull to false
        }
    }

    fun generateCoinGraph() { // Removed parameters, will get them from uiState.settings
        val currentSettings = _uiState.value.settings
        if (currentSettings == null || CoinProbabilityMode.valueOf(currentSettings.coinProbabilityMode) != CoinProbabilityMode.GRAPH_DISTRIBUTION || !currentSettings.isCoinGraphEnabled) {
            _uiState.update { it.copy(coinGraphData = CoinGraphDisplayData.Empty, errorEvent = Event("Graph not applicable with current settings.")) }
            return
        }

        val flipCountForSim = currentSettings.coinGraphFlipCount
        val poolToSim = _uiState.value.coinPool

        if (poolToSim.isEmpty()) {
            _uiState.update { it.copy(errorEvent = Event("Cannot generate graph with empty coin pool."), coinGraphData = CoinGraphDisplayData.Empty) }
            return
        }
        if (flipCountForSim <= 0) {
            _uiState.update { it.copy(errorEvent = Event("Graph flip count must be positive."), coinGraphData = CoinGraphDisplayData.Empty) }
            return
        }

        _uiState.update { it.copy(isGeneratingGraph = true) }
        viewModelScope.launch(Dispatchers.Default) {
            // headsCountFrequencies: Key = Number of heads in one multi-coin flip, Value = Frequency
            val headsCountFrequencies = mutableMapOf<Int, Int>()

            repeat(flipCountForSim) {
                var currentEventHeads = 0
                poolToSim.forEach { _ -> // Simulate flipping each coin in the defined pool for one event
                    if (Random.nextBoolean()) {
                        currentEventHeads++
                    }
                }
                headsCountFrequencies[currentEventHeads] = (headsCountFrequencies[currentEventHeads] ?: 0) + 1
            }

            val points = headsCountFrequencies.entries
                .sortedBy { it.key }
                .map { GraphDataPoint(value = it.key, frequency = it.value, label = "${it.key} Heads") }

            val plotType = try {
                GraphPlotType.valueOf(currentSettings.coinGraphPlotType)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid coinGraphPlotType '${currentSettings.coinGraphPlotType}', defaulting to HISTOGRAM.")
                GraphPlotType.HISTOGRAM
            }

            val newGraphData = when (plotType) {
                GraphPlotType.HISTOGRAM -> CoinGraphDisplayData.BarData(points)
                GraphPlotType.LINE_GRAPH -> CoinGraphDisplayData.LineData(points)
                GraphPlotType.QQ_PLOT -> {
                    Log.w(TAG, "Q-Q Plot for coin flips not yet implemented. Defaulting to Histogram.")
                    CoinGraphDisplayData.BarData(points) // Fallback for NYI
                }
            }

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(coinGraphData = newGraphData, isGeneratingGraph = false) }
            }
        }
    }

    private fun updateGraphIfNeeded() { // Renamed to reflect its new purpose
        val settings = _uiState.value.settings ?: return
        val probMode = CoinProbabilityMode.valueOf(settings.coinProbabilityMode)
        if (probMode != CoinProbabilityMode.GRAPH_DISTRIBUTION || !settings.isCoinGraphEnabled) {
            if (_uiState.value.coinGraphData !is CoinGraphDisplayData.Empty) {
                _uiState.update { it.copy(coinGraphData = CoinGraphDisplayData.Empty) }
            }
        }
        // Else, existing graph data remains until user refreshes or settings affecting its parameters change.
    }

    override fun onCleared() {
        Log.d(TAG, "ViewModel cleared for instanceId: $instanceId")
        super.onCleared()
    }
}