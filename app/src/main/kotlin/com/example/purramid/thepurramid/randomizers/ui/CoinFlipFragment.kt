// CoinFlipFragment.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.util.Log
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.MotionEvent // Needed for MotionEvent.ACTION_DOWN
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout // Keep for Free Form container
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.FragmentCoinFlipBinding
import com.example.purramid.thepurramid.randomizers.CoinProbabilityMode
import com.example.purramid.thepurramid.randomizers.RandomizerMode
import com.example.purramid.thepurramid.randomizers.viewmodel.CoinFace
import com.example.purramid.thepurramid.randomizers.viewmodel.CoinFlipUiState
import com.example.purramid.thepurramid.randomizers.viewmodel.CoinFlipViewModel
import com.example.purramid.thepurramid.randomizers.viewmodel.CoinInPool
import com.example.purramid.thepurramid.randomizers.viewmodel.CoinType
import com.example.purramid.thepurramid.randomizers.viewmodel.ProbabilityGridCell
import com.example.purramid.thepurramid.randomizers.viewmodel.RandomizerSettingsViewModel
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs // For drag slop
import kotlinx.coroutines.launch
import java.util.UUID

@AndroidEntryPoint
class CoinFlipFragment : Fragment() {

    private var _binding: FragmentCoinFlipBinding? = null
    private val binding get() = _binding!!

    private val coinFlipViewModel: CoinFlipViewModel by activityViewModels()
    private val settingsViewModel: RandomizerSettingsViewModel by activityViewModels()

    private lateinit var probabilityGridAdapter: ProbabilityGridAdapter
    private lateinit var coinAdapter: CoinAdapter

    // Map to store dynamically created ImageViews for free-form mode
    private val freeFormCoinViews = mutableMapOf<UUID, ImageView>()

    // Variables for simple touch-based dragging (alternative to Android's Drag and Drop framework for this specific case)
    private var activeDraggedCoinView: ImageView? = null
    private var dragInitialX: Float = 0f
    private var dragInitialY: Float = 0f
    private var dragInitialCoinX: Float = 0f
    private var dragInitialCoinY: Float = 0f
    private var isDraggingCoin = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCoinFlipBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCoinAdapter()
        setupUIListeners()
        setupProbabilityGridRecyclerView()
        observeViewModel()
        coinFlipViewModel.refreshSettings()

        // Set touch listener for free-form dragging on its dedicated container
        binding.freeFormDisplayContainer.setOnTouchListener(freeFormTouchListener)
    }

    private fun setupCoinAdapter() {
        val initialSettings = coinFlipViewModel.uiState.value.settings
        coinAdapter = CoinAdapter(
            coinColor = initialSettings?.coinColor ?: ContextCompat.getColor(requireContext(), R.color.goldenrod),
            animateFlips = initialSettings?.isFlipAnimationEnabled ?: true,
            getCoinDrawableResFunction = ::getCoinDrawableResource // Pass function reference
        )
        binding.coinDisplayAreaRecyclerView.apply {
            adapter = coinAdapter
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            // Optional: Add ItemDecoration for spacing
            // addItemDecoration(HorizontalSpaceItemDecoration(resources.getDimensionPixelSize(R.dimen.small_padding)))
        }
    }

    private fun setupUIListeners() {
        binding.coinFlipCloseButton.setOnClickListener { activity?.finish() }
        binding.coinFlipSettingsButton.setOnClickListener {
            coinFlipViewModel.uiState.value.settings?.instanceId?.let { instanceId ->
                try {
                    val action = CoinFlipFragmentDirections.actionCoinFlipFragmentToRandomizerSettingsFragment(instanceId.toString())
                    findNavController().navigate(action)
                } catch (e: Exception) {
                    Log.e("CoinFlipFragment", "Navigation to Settings failed.", e)
                }
            }
        }
        binding.coinFlipActionButton.setOnClickListener { handleActionButtonClick() }
        binding.manageCoinPoolButton.setOnClickListener {
            coinFlipViewModel.uiState.value.settings?.instanceId?.let {
                CoinPoolDialogFragment.newInstance(it).show(parentFragmentManager, CoinPoolDialogFragment.TAG)
            }
        }
        // The drag listener should be on the container FOR free form mode.
        // For now, let's assume `binding.coinDisplayAreaFrame` is that container if you add it.
        // binding.coinDisplayAreaFrame.setOnDragListener(coinDragListener)
    }

    private fun handleActionButtonClick() {
        val uiState = coinFlipViewModel.uiState.value
        val settings = uiState.settings ?: return
        val currentProbMode = CoinProbabilityMode.valueOf(settings.coinProbabilityMode)

        if (settings.isCoinFreeFormEnabled) {
            coinFlipViewModel.toggleFreeFormCoinFaces()
        } else if (uiState.isProbabilityGridFull &&
            (currentProbMode == CoinProbabilityMode.GRID_3X3 ||
                    currentProbMode == CoinProbabilityMode.GRID_6X6 ||
                    currentProbMode == CoinProbabilityMode.GRID_10X10)) {
            coinFlipViewModel.resetProbabilityGrid()
        } else {
            coinFlipViewModel.flipCoins() // This will update uiState, triggering animation via observer
        }
    }

    private fun setupProbabilityGridRecyclerView() {
        probabilityGridAdapter = ProbabilityGridAdapter { cell ->
            if (!cell.isFilled) {
                coinFlipViewModel.handleGridCellTap(cell.rowIndex, cell.colIndex)
            }
        }
        binding.probabilityGridLayout.adapter = probabilityGridAdapter
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    coinFlipViewModel.uiState.collect { state ->
                        updateUi(state)
                    }
                }
                launch {
                    settingsViewModel.settings.collect { hostSettings ->
                        if (hostSettings?.mode == RandomizerMode.COIN_FLIP &&
                            hostSettings.instanceId == coinFlipViewModel.uiState.value.settings?.instanceId) {
                            coinFlipViewModel.refreshSettings()
                        }
                    }
                }
            }
        }
    }

    private fun updateUi(state: CoinFlipUiState) {
        val settings = state.settings
        if (settings == null) {
            binding.coinFlipActionButton.isEnabled = false
            binding.coinFlipTitleTextView.text = getString(R.string.loading_settings)
            binding.coinDisplayAreaRecyclerView.visibility = View.GONE
            binding.freeFormDisplayContainer.visibility = View.GONE
            return
        }
        binding.coinFlipActionButton.isEnabled = !state.isFlipping && state.coinPool.isNotEmpty()
        // binding.coinFlipTitleTextView.text = getString(R.string.randomizer_mode_coin_flip)

        val currentProbMode = CoinProbabilityMode.valueOf(settings.coinProbabilityMode)
        when {
            settings.isCoinFreeFormEnabled -> binding.coinFlipActionButton.text = state.freeFormButtonText
            state.isProbabilityGridFull && currentProbMode.name.startsWith("GRID_") -> {
                binding.coinFlipActionButton.setText(R.string.reset_action)
            }
            else -> binding.coinFlipActionButton.setText(R.string.flip_coins_action)
        }

        coinAdapter.updateCoinAppearanceProperties(settings.coinColor, settings.isFlipAnimationEnabled)

        // Toggle visibility between RecyclerView (standard) and a dedicated FrameLayout (free-form)
        // TODO add a FrameLayout to your fragment_coin_flip.xml, e.g., android:id="@+id/freeFormDisplayContainer"
        if (settings.isCoinFreeFormEnabled) {
            binding.coinDisplayAreaRecyclerView.visibility = View.GONE
            binding.freeFormDisplayContainer.visibility = View.VISIBLE // Make sure this ID exists in XML
            // binding.freeFormDisplayContainer.setOnDragListener(coinDragListener) // Set listener here
            updateFreeFormCoinViews(state.coinPool, settings.coinColor)
        } else {
            binding.coinDisplayAreaRecyclerView.visibility = View.VISIBLE
            binding.freeFormDisplayContainer.visibility = View.GONE // Make sure this ID exists in XML
            // binding.freeFormDisplayContainer.setOnDragListener(null) // Clear listener

            coinAdapter.submitList(state.coinPool.toList()) // Submit a new list for DiffUtil
                if (state.lastFlipResult != null && !state.isFlipping && settings.isFlipAnimationEnabled) {
                    state.coinPool.forEach { coinWithNewFace ->
                        coinAdapter.updateItemAndAnimate(coinWithNewFace.id, coinWithNewFace.currentFace)
                }
            // } else if (!state.isFlipping) {
                // If not flipping and not animating, ensure the adapter has the correct static data.
                // This case is mostly covered by submitList, but an explicit re-bind might be needed if only color changed.
            //    coinAdapter.notifyDataSetChanged() // Or more specific notifyItemRangeChanged if only properties changed
            }
        }
        clearFreeFormCoinViews()

        binding.probabilityTwoColumnLayout.isVisible = currentProbMode == CoinProbabilityMode.TWO_COLUMNS && !settings.isCoinFreeFormEnabled && !settings.isCoinAnnouncementEnabled
        val isGridModeActive = (currentProbMode == CoinProbabilityMode.GRID_3X3 || currentProbMode == CoinProbabilityMode.GRID_6X6 || currentProbMode == CoinProbabilityMode.GRID_10X10)
        binding.probabilityGridLayout.isVisible = isGridModeActive && !settings.isCoinFreeFormEnabled && !settings.isCoinAnnouncementEnabled
        binding.probabilityGraphLayout.isVisible = currentProbMode == CoinProbabilityMode.GRAPH_DISTRIBUTION && !settings.isCoinFreeFormEnabled && !settings.isCoinAnnouncementEnabled

        if (binding.probabilityGraphLayout.isVisible) {
            binding.coinGraphProgressBar.isVisible = state.isGeneratingGraph
            binding.buttonRefreshCoinGraph.setOnClickListener {
                // No need to pass parameters, ViewModel will get them from its state.settings
                coinFlipViewModel.generateCoinGraph()
            }

            val chartView = binding.coinFlipBarChart

            when (val graphData = state.coinGraphData) {
                is CoinGraphDisplayData.BarData -> {
                    if (graphData.points.isEmpty() && !state.isGeneratingGraph) {
                        chartView.clear()
                        chartView.data = null
                        chartView.setNoDataText("@string/graph_distribution_placeholder")
                        chartView.invalidate()
                    } else if (graphData.points.isNotEmpty()) {
                        val entries = ArrayList<BarEntry>()
                        val labels = ArrayList<String>()
                        graphData.points.forEachIndexed { index, point ->
                            // For BarChart, X is typically an index if labels are provided separately
                            entries.add(BarEntry(index.toFloat(), point.frequency.toFloat()))
                            labels.add(point.label)
                        }
                        val dataSet = BarDataSet(entries, "@string/graph_coin_flip_heads") // Use String Resource
                        dataSet.colors = ColorTemplate.MATERIAL_COLORS.toList()
                        dataSet.valueTextSize = 10f

                        val barData = BarData(dataSet)
                        chartView.data = barData
                        chartView.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                        chartView.xAxis.labelCount = labels.size.coerceAtLeast(1)
                        chartView.axisLeft.axisMinimum = 0f
                        chartView.axisRight.isEnabled = false
                        chartView.animateY(1000)
                        chartView.invalidate()
                    }
                }
                is CoinGraphDisplayData.LineData -> {
                    if (graphData.points.isEmpty() && !state.isGeneratingGraph) {
                        chartView.clear()
                        chartView.data = null
                        chartView.setNoDataText("@string/graph_distribution_placeholder") // Use String Resource
                        chartView.invalidate()
                    } else if (graphData.points.isNotEmpty()) {
                        val entries = ArrayList<com.github.mikephil.charting.data.Entry>()
                        // For LineChart, X values are the actual number of heads
                        graphData.points.forEach { point ->
                            entries.add(com.github.mikephil.charting.data.Entry(point.value.toFloat(), point.frequency.toFloat()))
                        }
                        val dataSet = LineDataSet(entries, "@string/graph_coin_flip_heads")
                        dataSet.color = ContextCompat.getColor(requireContext(), R.color.design_default_color_secondary)
                        dataSet.valueTextColor = ContextCompat.getColor(requireContext(), R.color.design_default_color_on_surface)
                        dataSet.lineWidth = 2f
                        dataSet.setCircleColor(ContextCompat.getColor(requireContext(), R.color.design_default_color_secondary))
                        dataSet.circleRadius = 4f
                        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER

                        val lineData = LineData(dataSet)
                        chartView.data = lineData
                        chartView.xAxis.valueFormatter = com.github.mikephil.charting.formatter.DefaultAxisValueFormatter(0) // Show numbers as is
                        chartView.axisLeft.axisMinimum = 0f
                        chartView.axisRight.isEnabled = false
                        chartView.animateX(1000)
                        chartView.invalidate()
                    }
                }
                is CoinGraphDisplayData.Empty -> {
                    if (!state.isGeneratingGraph) {
                        chartView.clear()
                        chartView.data = null
                        chartView.setNoDataText("@string/graph_distribution_placeholder")
                        chartView.invalidate()
                    }
                }
            }
        } else {
            // Ensure chart is cleared if the graph section is not visible
            if (binding.coinFlipBarChart.data != null) {
                binding.coinFlipBarChart.clear()
                binding.coinFlipBarChart.data = null
                binding.coinFlipBarChart.invalidate()
            }
        }

        if (binding.probabilityGridLayout.isVisible) {
            if ((binding.probabilityGridLayout.layoutManager as? GridLayoutManager)?.spanCount != state.probabilityGridColumns && state.probabilityGridColumns > 0) {
                binding.probabilityGridLayout.layoutManager = GridLayoutManager(context, state.probabilityGridColumns)
            }
            probabilityGridAdapter.submitList(state.probabilityGrid)
        }

        val announcementShouldBeVisible = settings.isCoinAnnouncementEnabled && state.lastFlipResult != null && !state.isFlipping && !settings.isCoinFreeFormEnabled && currentProbMode == CoinProbabilityMode.NONE
        if (announcementShouldBeVisible) {
            binding.coinFlipAnnouncementTextView.text = getString(R.string.coin_flip_announce_format, state.lastFlipResult!!.totalHeads, state.lastFlipResult.totalTails)
            binding.coinFlipAnnouncementTextView.visibility = View.VISIBLE
        } else {
            binding.coinFlipAnnouncementTextView.visibility = View.GONE
        }

        state.errorEvent?.getContentIfNotHandled()?.let {
            Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun getCoinDrawableResource(type: CoinType, face: CoinFace): Int {
        val baseName = when (type) {
            CoinType.BIT_1 -> "b1_coin_flip" // Updated as per your convention
            CoinType.BIT_5 -> "b5_coin_flip"
            CoinType.BIT_10 -> "b10_coin_flip"
            CoinType.BIT_25 -> "b25_coin_flip"
            CoinType.MEATBALL_1 -> "mb1_coin_flip"
            CoinType.MEATBALL_2 -> "mb2_coin_flip"
        }
        val faceName = if (face == CoinFace.HEADS) "_heads" else "_tails"
        val resName = baseName + faceName
        val currentContext = context ?: return R.drawable.coin_flip_heads // Should not happen in fragment
        val resId = resources.getIdentifier(resName, "drawable", requireContext().packageName)
        return if (resId != 0) resId else {
            Log.w("CoinFlipFragment", "Drawable not found: $resName. Using generic placeholder.")
            // Fallback to generic placeholder from your assets (if you have them)
            // For now, using the ones you provided earlier as example fallbacks
            if (face == CoinFace.HEADS) R.drawable.coin_flip_heads else R.drawable.coin_flip_tails
        }
    }

    private fun updateFreeFormCoinViews(pool: List<CoinInPool>, colorInt: Int) {
        val container = binding.freeFormDisplayContainer
        val currentCoinIdsInPool = pool.map { it.id }.toSet()

        // Remove views for coins no longer in the pool
        val viewsToRemove = freeFormCoinViews.filterKeys { it !in currentCoinIdsInPool }
        viewsToRemove.forEach { (id, view) ->
            container.removeView(view)
            freeFormCoinViews.remove(id)
        }

        pool.forEachIndexed { index, coin ->
            val coinView = freeFormCoinViews.getOrPut(coin.id) {
                ImageView(requireContext()).apply {
                    val sizePx = resources.getDimensionPixelSize(R.dimen.coin_freeform_size)
                    layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                    contentDescription = getString(R.string.coin_image_desc, coin.type.label, coin.currentFace.name.lowercase())
                    // Touch listener for individual coin dragging is now handled by the container's listener
                    container.addView(this)
                }
            }
            coinView.setImageResource(getCoinDrawableResource(coin.type, coin.currentFace))
            applyCoinTint(coinView, colorInt, settingsViewModel.settings.value?.coinColor)
            coinView.tag = coin.id // Store coin ID in tag for easy retrieval in touch listener

            // Initial positioning or update position if already set
            if (coin.xPos == 0f && coin.yPos == 0f && !isDraggingCoin) { // Only set initial if not already positioned and not dragging
                // Simple staggered initial position
                coinView.x = (index % 4) * (resources.getDimensionPixelSize(R.dimen.coin_freeform_size) + 10f)
                coinView.y = (index / 4) * (resources.getDimensionPixelSize(R.dimen.coin_freeform_size) + 10f)
                coinFlipViewModel.updateCoinPositionInFreeForm(coin.id, coinView.x, coinView.y) // Save initial position
            } else {
                coinView.x = coin.xPos
                coinView.y = coin.yPos
            }
            coinView.visibility = View.VISIBLE
        }
    }

    private fun clearFreeFormCoinViewsMemory() {
        binding.freeFormDisplayContainer.removeAllViews() // Assuming this ID exists
        freeFormCoinViews.clear()
    }

    @SuppressLint("ClickableViewAccessibility")
    private val freeFormTouchListener = View.OnTouchListener { view, event ->
        // This listener is on freeFormDisplayContainer
        val container = view as FrameLayout
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                var touchedCoinView: ImageView? = null
                var touchedCoinId: UUID? = null

                // Check if touch is on an existing coin
                for ((id, iv) in freeFormCoinViews) {
                    if (x >= iv.x && x <= iv.x + iv.width && y >= iv.y && y <= iv.y + iv.height) {
                        touchedCoinView = iv
                        touchedCoinId = id
                        break
                    }
                }

                if (touchedCoinView != null && touchedCoinId != null) {
                    activeDraggedCoinView = touchedCoinView
                    isDraggingCoin = true
                    dragInitialX = x
                    dragInitialY = y
                    dragInitialCoinX = touchedCoinView.x
                    dragInitialCoinY = touchedCoinView.y
                    touchedCoinView.bringToFront() // Bring dragged coin to front
                    return@OnTouchListener true // Consumed
                }
                return@OnTouchListener false // Did not touch a coin, let other listeners handle (if any)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingCoin && activeDraggedCoinView != null) {
                    val newX = dragInitialCoinX + (x - dragInitialX)
                    val newY = dragInitialCoinY + (y - dragInitialY)

                    activeDraggedCoinView!!.x = newX.coerceIn(0f, (container.width - activeDraggedCoinView!!.width).toFloat())
                    activeDraggedCoinView!!.y = newY.coerceIn(0f, (container.height - activeDraggedCoinView!!.height).toFloat())
                    return@OnTouchListener true // Consumed
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingCoin && activeDraggedCoinView != null) {
                    val finalX = activeDraggedCoinView!!.x
                    val finalY = activeDraggedCoinView!!.y
                    (activeDraggedCoinView!!.tag as? UUID)?.let { coinId ->
                        coinFlipViewModel.updateCoinPositionInFreeForm(coinId, finalX, finalY)
                    }
                }
                activeDraggedCoinView = null
                isDraggingCoin = false
                return@OnTouchListener true // Consumed if dragging was active
            }
        }
        false
    }

    private fun applyCoinTint(imageView: ImageView, colorIntFromSetting: Int?, defaultColorFromSettings: Int?) {
        val actualColor = colorIntFromSetting ?: defaultColorFromSettings ?: ContextCompat.getColor(requireContext(), R.color.goldenrod)
        if (actualColor != Color.TRANSPARENT) {
            imageView.colorFilter = PorterDuffColorFilter(actualColor, PorterDuff.Mode.SRC_IN)
        } else {
            imageView.clearColorFilter()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clearFreeFormCoinViewsMemory()
        _binding = null
    }
}