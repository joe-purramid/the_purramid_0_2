// DiceMainFragment.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter // For labels
import com.github.mikephil.charting.utils.ColorTemplate // For colors
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.DEFAULT_EMPTY_JSON_MAP
import com.example.purramid.thepurramid.data.db.SpinSettingsEntity
import com.example.purramid.thepurramid.databinding.FragmentDiceMainBinding
import com.example.purramid.thepurramid.databinding.ItemDieResultBinding
import com.example.purramid.thepurramid.randomizers.viewmodel.DiceGraphDisplayData // From ViewModel
import com.example.purramid.thepurramid.randomizers.viewmodel.DiceRollResults
import com.example.purramid.thepurramid.randomizers.viewmodel.DiceViewModel
import com.example.purramid.thepurramid.randomizers.viewmodel.ProcessedDiceResult
import com.example.purramid.thepurramid.ui.PurramidPalette // Assuming this exists for default colors
import com.google.android.flexbox.FlexboxLayout // For LayoutParams
import com.google.android.flexbox.JustifyContent
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.AlignContent
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@AndroidEntryPoint
class DiceMainFragment : Fragment() {

    private var _binding: FragmentDiceMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DiceViewModel by viewModels()
    private val gson = Gson()

    private enum class LastResultType { NONE, POOL, PERCENTILE }
    private var lastResultType = LastResultType.NONE
    private val animationDuration = 1000L // Default animation duration from original file
    private val announcementDisplayDuration = 3000L

    private val announcementHandler = Handler(Looper.getMainLooper())
    private var announcementRunnable: Runnable? = null

    private lateinit var diceBarChart: BarChart // Reference to the chart

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDiceMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentDiceMainBinding.bind(view)

        diceBarChart = binding.diceBarChart // Initialize the chart view from binding
        setupDiceChartAppearance() // New method to configure chart style

        setupListeners()
        observeViewModel()
    }

    private fun setupDiceChartAppearance() {
        diceBarChart.description.isEnabled = false
        diceBarChart.setDrawGridBackground(false)
        diceBarChart.setDrawBarShadow(false)
        diceBarChart.setFitBars(true) // Makes bars fit into the screen width

        // X-axis configuration
        val xAxis = diceBarChart.xAxis
        xAxis.position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.granularity = 1f // minimum interval between axis values.

        // Y-axis (Left) configuration
        val leftAxis = diceBarChart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.axisMinimum = 0f // start at 0

        // Y-axis (Right) configuration - disable it
        diceBarChart.axisRight.isEnabled = false

        // Legend configuration
        diceBarChart.legend.isEnabled = true // Enable if you have multiple datasets (e.g., for GroupedBarData)
        // Or false if only one dataset and label is clear
    }

    override fun onDestroyView() {
        super.onDestroyView()
        announcementRunnable?.let { announcementHandler.removeCallbacks(it) }
        _binding = null
    }

    private fun setupListeners() {
        binding.diceRollButton.setOnClickListener {
            clearAnnouncement()
            handleRoll()
        }
        binding.diceCloseButton.setOnClickListener {
            viewModel.handleManualClose()
            activity?.finish()
        }
        binding.diceSettingsButton.setOnClickListener {
            viewModel.settings.value?.instanceId?.let { navigateToSettings(it) }
                ?: Log.e("DiceMainFragment", "Cannot navigate to settings: Instance ID not available")
        }
        binding.dicePoolButton.setOnClickListener {
            viewModel.settings.value?.instanceId?.let { instanceId ->
                DicePoolDialogFragment.newInstance(instanceId)
                    .show(parentFragmentManager, DicePoolDialogFragment.TAG)
            } ?: Log.e("DiceMainFragment", "Cannot open Dice Pool: Instance ID not available")
        }
        binding.diceResetButton.setOnClickListener {
            // TODO: Implement graph reset logic if applicable
            Toast.makeText(context, "Reset Graph (TODO)", Toast.LENGTH_SHORT).show()
        }
        binding.diceAnnouncementTextView.setOnClickListener {
            clearAnnouncement()
        }
    }

    private fun observeViewModel() {
        val lifecycleOwner = viewLifecycleOwner // Cache for observer lambda

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.rawDicePoolResults.observe(lifecycleOwner) { rawResults ->
                        if (viewModel.settings.value?.isDiceAnimationEnabled == false ||
                            (lastResultType == LastResultType.POOL || lastResultType == LastResultType.NONE || rawResults != null)) {
                            if (viewModel.processedDiceResult.value?.isPercentile == false) {
                                displayDicePoolResults(rawResults) // This will be modified
                                if (rawResults != null) lastResultType = LastResultType.POOL
                            }
                        }
                    }
                }
                launch {
                    viewModel.rawPercentileResultComponents.observe(lifecycleOwner) { rawPercentileComponents ->
                        if (viewModel.settings.value?.isDiceAnimationEnabled == false ||
                            (lastResultType == LastResultType.PERCENTILE || lastResultType == LastResultType.NONE || rawPercentileComponents != null)) {
                            if (viewModel.processedDiceResult.value?.isPercentile == true) {
                                displayPercentileResult(viewModel.processedDiceResult.value)
                                if (rawPercentileComponents != null) lastResultType = LastResultType.PERCENTILE
                            }
                        }
                    }
                }
                launch {
                    viewModel.settings.observe(lifecycleOwner) { settings ->
                        if (settings != null) {
                            binding.diceRollButton.isEnabled = !(settings.isPercentileDiceEnabled == false &&
                                    (viewModel.parseDicePoolConfig(settings.dicePoolConfigJson)?.values?.sum()
                                        ?: 0) == 0)
                            binding.diceGraphViewContainer.visibility = View.GONE
                            binding.diceResetButton.visibility = View.GONE
                            if (!settings.isAnnounceEnabled) {
                                clearAnnouncement()
                            }
                            if (!settings.isDiceCritCelebrationEnabled) {
                                binding.konfettiViewDice.stopGracefully()
                            }
                            return@observe
                        }
                        val isGraphCurrentlyEnabled = settings.isDiceGraphEnabled
                        binding.diceGraphViewContainer.visibility = if (isGraphCurrentlyEnabled) View.VISIBLE else View.GONE
                        binding.diceResetButton.visibility = if (isGraphCurrentlyEnabled) View.VISIBLE else View.GONE

                        if (isGraphCurrentlyEnabled) {
                            diceBarChart.invalidate() // Force redraw if it became visible with existing data
                        } else {
                            diceBarChart.clear()
                            diceBarChart.data = null
                            diceBarChart.invalidate()
                        }
                        // Cache settings if needed locally, though viewModel.settings.value is often preferred
                        this@DiceMainFragment.settings = settings // Assuming 'this.settings' is a Fragment property
                    }
                }
                launch {
                    viewModel.processedDiceResult.observe(lifecycleOwner) { processedResult ->
                        val currentSettings = viewModel.settings.value
                        if (processedResult != null && currentSettings != null) {
                            if (currentSettings.isAnnounceEnabled) {
                                showAnnouncement(processedResult.announcementString)
                                if (currentSettings.isDiceCritCelebrationEnabled && processedResult.d20CritsRolled > 0) {
                                    startCritCelebration()
                                }
                            } else {
                                clearAnnouncement()
                            }
                        } else {
                            clearAnnouncement()
                        }
                    }
                }
                launch {
                    viewModel.errorEvent.observe(lifecycleOwner) { event ->
                        event?.getContentIfNotHandled()?.let { errorMessage ->
                            showErrorSnackbar(errorMessage)
                        }
                    }
                }
                launch {
                    viewModel.diceGraphData.observe(viewLifecycleOwner) { graphData ->
                        // Ensure graph should be visible based on current settings
                        // (The settings observer already handles overall visibility of the container)
                        if (viewModel.settings.value?.isDiceGraphEnabled != true) {
                            // Optionally, ensure chart is cleared if it somehow gets data while container is hidden
                            if (diceBarChart.data != null) {
                                diceBarChart.clear()
                                diceBarChart.data = null
                                diceBarChart.invalidate()
                            }
                            return@observe
                        }
                        // diceBarChart.visibility = View.VISIBLE // Container visibility handled by settings observer

                        when (graphData) {
                            is DiceGraphDisplayData.BarData -> {
                                if (graphData.points.isEmpty()) {
                                    diceBarChart.clear()
                                    diceBarChart.data = null
                                    diceBarChart.setNoDataText("No graph data yet. Roll some dice!")
                                    diceBarChart.invalidate()
                                    return@observe
                                }
                                val entries = ArrayList<BarEntry>()
                                val labels = ArrayList<String>()
                                graphData.points.forEachIndexed { index, point ->
                                    entries.add(BarEntry(index.toFloat(), point.frequency.toFloat()))
                                    labels.add(point.label)
                                }

                                val dataSet = BarDataSet(entries, graphData.dataSetLabel)
                                dataSet.colors = ColorTemplate.MATERIAL_COLORS.toList()
                                dataSet.valueTextSize = 10f

                                val barData = BarData(dataSet)
                                diceBarChart.data = barData
                                diceBarChart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                                diceBarChart.xAxis.labelCount = labels.size.coerceAtLeast(1) // Ensure at least 1 for formatter

                                diceBarChart.animateY(1000)
                                diceBarChart.invalidate()
                            }
                            is DiceGraphDisplayData.GroupedBarData -> {
                                if (graphData.groupedDataSets.isEmpty()) {
                                    diceBarChart.clear()
                                    diceBarChart.data = null
                                    diceBarChart.setNoDataText("No grouped data available.")
                                    diceBarChart.invalidate()
                                    return@observe
                                }

                                val allLabels = mutableSetOf<String>()
                                // Determine all unique x-axis labels first to ensure consistent indexing
                                graphData.groupedDataSets.values.forEach { points ->
                                    points.forEach { point -> allLabels.add(point.label) }
                                }
                                val distinctXLabels = allLabels.toList().sorted() // Or maintain a specific order

                                val dataSets = mutableListOf<IBarDataSet>() // Use IBarDataSet for the list

                                var colorIndex = 0
                                graphData.groupedDataSets.forEach { (groupLabel, points) ->
                                    val entries = ArrayList<BarEntry>()
                                    // Map points to their index in distinctXLabels
                                    distinctXLabels.forEachIndexed { xIndex, label ->
                                        val point = points.find { it.label == label }
                                        if (point != null) {
                                            entries.add(BarEntry(xIndex.toFloat(), point.frequency.toFloat()))
                                        } else {
                                            // Add a zero entry if this group doesn't have this label,
                                            // or handle as per chart library requirement for sparse grouped data
                                            // entries.add(BarEntry(xIndex.toFloat(), 0f))
                                        }
                                    }
                                    // Alternative: Only add entries for labels present in this group
                                    // points.forEach { point ->
                                    //    val xIndex = distinctXLabels.indexOf(point.label)
                                    //    if (xIndex != -1) entries.add(BarEntry(xIndex.toFloat(), point.frequency.toFloat()))
                                    // }


                                    if (entries.isNotEmpty()) { // Only add dataset if it has entries
                                        val dataSet = BarDataSet(entries, groupLabel)
                                        dataSet.color = ColorTemplate.MATERIAL_COLORS[colorIndex % ColorTemplate.MATERIAL_COLORS.size]
                                        dataSet.valueTextSize = 10f
                                        dataSets.add(dataSet)
                                        colorIndex++
                                    }
                                }


                                if (dataSets.isEmpty()) {
                                    diceBarChart.clear()
                                    diceBarChart.data = null
                                    diceBarChart.setNoDataText("No data for grouped chart.")
                                    diceBarChart.invalidate()
                                    return@observe
                                }

                                val barData = BarData(dataSets)
                                diceBarChart.data = barData

                                diceBarChart.xAxis.valueFormatter = IndexAxisValueFormatter(distinctXLabels)
                                diceBarChart.xAxis.granularity = 1f
                                diceBarChart.xAxis.isGranularityEnabled = true
                                diceBarChart.xAxis.setCenterAxisLabels(true)
                                diceBarChart.xAxis.axisMinimum = 0f
                                diceBarChart.xAxis.axisMaximum = distinctXLabels.size.toFloat()


                                val groupSpace = 0.1f
                                val barSpace = 0.05f // space between bars of the same group
                                // barWidth should be calculated to fit N datasets
                                // (barWidth + barSpace) * numDataSets + groupSpace = 1.0 (the space for a whole group)
                                val numDataSets = dataSets.size
                                if (numDataSets > 0) {
                                    val barWidth = (1.0f - groupSpace - (barSpace * (numDataSets -1) )) / numDataSets
                                    barData.barWidth = barWidth
                                }


                                if (numDataSets > 1) { // Only group if there are multiple datasets
                                    diceBarChart.groupBars(0f, groupSpace, barSpace)
                                }

                                diceBarChart.animateY(1000)
                                diceBarChart.invalidate()
                            }
                            is DiceGraphDisplayData.Empty -> {
                                diceBarChart.clear()
                                diceBarChart.data = null
                                diceBarChart.setNoDataText("No graph data yet. Roll some dice!")
                                diceBarChart.invalidate()
                            }
                            is DiceGraphDisplayData.LineChartData -> {
                                if (graphData.points.isEmpty()) {
                                    diceBarChart.clear()
                                    diceBarChart.data = null // Important to null out data
                                    diceBarChart.setNoDataText("No data for line chart.") // Use String Resource
                                    diceBarChart.invalidate()
                                    return@observe
                                }
                                val entries = ArrayList<com.github.mikephil.charting.data.Entry>()
                                // For LineChart, X values are the actual dice sum values
                                graphData.points.forEach { point -> // Assuming points are already sorted by value if needed by plot
                                    entries.add(com.github.mikephil.charting.data.Entry(point.value.toFloat(), point.frequency.toFloat()))
                                }

                                val dataSet = LineDataSet(entries, graphData.dataSetLabel)
                                dataSet.color = ContextCompat.getColor(requireContext(), R.color.design_default_color_primary) // Use theme color
                                dataSet.valueTextColor = ContextCompat.getColor(requireContext(), R.color.design_default_color_on_surface) // Use theme color
                                dataSet.lineWidth = 2f
                                dataSet.setCircleColor(ContextCompat.getColor(requireContext(), R.color.design_default_color_primary))
                                dataSet.circleRadius = 4f
                                dataSet.setDrawCircleHole(false)
                                dataSet.valueTextSize = 10f
                                // dataSet.setDrawFilled(true) // Optional fill
                                // dataSet.fillColor = ContextCompat.getColor(requireContext(), R.color.design_default_color_primary_dark)
                                // dataSet.fillAlpha = 50
                                dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER // Smoother line

                                val lineData = LineData(dataSet)
                                diceBarChart.data = lineData // BarChart view can render LineData

                                // X-axis formatting for line chart where X is the actual value
                                val xAxis = diceBarChart.xAxis
                                xAxis.valueFormatter = com.github.mikephil.charting.formatter.DefaultAxisValueFormatter(0) // Show numbers as is
                                // Adjust granularity and label count if X values are sparse or too dense
                                // Example: If sums range from 2 to 12, this is fine. If wider, may need adjustments.
                                // xAxis.granularity = 1f
                                // xAxis.setLabelCount(graphData.points.size, false) // Force label count might be too much

                                diceBarChart.axisLeft.axisMinimum = 0f // Ensure Y starts at 0
                                diceBarChart.axisRight.isEnabled = false
                                diceBarChart.animateX(1000) // Animate X for line chart
                                diceBarChart.invalidate()
                            }
                            is DiceGraphDisplayData.NotApplicable -> {
                                diceBarChart.clear()
                                diceBarChart.data = null
                                diceBarChart.setNoDataText("Graph type not applicable for current settings.")
                                diceBarChart.invalidate()
                            }
                            null -> {
                                diceBarChart.clear()
                                diceBarChart.data = null
                                diceBarChart.invalidate()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleRoll() {
        val currentSettings = viewModel.settings.value
        val isAnimationEnabled = currentSettings?.isDiceAnimationEnabled ?: false
        clearAnnouncement()
        // Clear display area before animation or direct display
        binding.diceDisplayArea.removeAllViews()


        if (isAnimationEnabled) {
            // Create temporary views based on the *expected* final layout structure
            val tempDiceGroups = createTemporaryDiceGroupViewsForAnimation(currentSettings)
            tempDiceGroups.forEach { binding.diceDisplayArea.addView(it) }
            // Apply initial flexbox settings for animation phase based on number of groups
            applyFlexboxSettingsForDiceGroups(tempDiceGroups.size)

            val allAnimatedViews = tempDiceGroups.flatMap { group ->
                (0 until (group as ViewGroup).childCount).map { group.getChildAt(it) }
            }
            animateDiceViews(allAnimatedViews) { // Animate individual dice within groups
                viewModel.rollDice() // ViewModel roll will trigger observers to call displayDicePoolResults
            }
        } else {
            viewModel.rollDice() // This will trigger observers to call displayDicePoolResults
        }
    }

    private fun showAnnouncement(message: String) {
        announcementRunnable?.let { announcementHandler.removeCallbacks(it) }
        binding.diceAnnouncementTextView.text = message
        if (!binding.diceAnnouncementTextView.isVisible) {
            binding.diceAnnouncementTextView.alpha = 0f
            binding.diceAnnouncementTextView.visibility = View.VISIBLE
            binding.diceAnnouncementTextView.animate()
                .alpha(1f)
                .setDuration(300)
                .setListener(null)
        }
        announcementRunnable = Runnable { clearAnnouncement() }
        announcementHandler.postDelayed(announcementRunnable!!, announcementDisplayDuration)
    }

    private fun clearAnnouncement() {
        announcementRunnable?.let { announcementHandler.removeCallbacks(it) }
        announcementRunnable = null
        if (_binding == null || !binding.diceAnnouncementTextView.isVisible) return // Check binding

        binding.diceAnnouncementTextView.animate()
            .alpha(0f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (_binding != null) {
                        binding.diceAnnouncementTextView.visibility = View.GONE
                        binding.diceAnnouncementTextView.text = ""
                    }
                }
            })
    }

    // Create groups of dice for animation
    private fun createTemporaryDiceGroupViewsForAnimation(settings: SpinSettingsEntity?): List<ViewGroup> {
        val groupViews = mutableListOf<ViewGroup>()
        val inflater = LayoutInflater.from(context)
        val colorConfig = parseDiceColorConfig(settings?.diceColorConfigJson)

        if (settings?.isPercentileDiceEnabled == true) {
            // Percentile dice are typically shown as a pair, so one "group" for them
            val percentileGroup = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = FlexboxLayout.LayoutParams(
                    FlexboxLayout.LayoutParams.WRAP_CONTENT,
                    FlexboxLayout.LayoutParams.WRAP_CONTENT
                )
            }
            repeat(2) { index ->
                val itemBinding = ItemDieResultBinding.inflate(inflater, percentileGroup, false)
                val dieTypeKey = if (index == 0) DicePoolDialogFragment.D10_TENS_KEY else DicePoolDialogFragment.D10_UNITS_KEY
                itemBinding.dieResultImageView.setImageResource(getDieResultDrawable(dieTypeKey, Random.nextInt(0, 10)))
                applyTint(itemBinding.dieResultImageView, colorConfig[dieTypeKey])
                percentileGroup.addView(itemBinding.root)
            }
            groupViews.add(percentileGroup)
        } else {
            val poolConfig = viewModel.parseDicePoolConfig(settings?.dicePoolConfigJson)
            poolConfig?.toSortedMap()?.forEach { (sides, count) -> // Sort for consistent order
                if (count > 0) {
                    val dieTypeGroup = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL // Dice of the same type in a row
                        layoutParams = FlexboxLayout.LayoutParams(
                            FlexboxLayout.LayoutParams.WRAP_CONTENT,
                            FlexboxLayout.LayoutParams.WRAP_CONTENT
                        )
                        // Add some spacing between dice in the same group if desired
                        // (e.g., set a margin on itemBinding.root)
                    }
                    repeat(count) {
                        val itemBinding = ItemDieResultBinding.inflate(inflater, dieTypeGroup, false)
                        itemBinding.dieResultImageView.setImageResource(getDieResultDrawable(sides, Random.nextInt(1, sides + 1)))
                        applyTint(itemBinding.dieResultImageView, colorConfig[sides])
                        dieTypeGroup.addView(itemBinding.root)
                    }
                    groupViews.add(dieTypeGroup)
                }
            }
        }
        return groupViews
    }

    private fun animateDiceViews(diceViews: List<View>, onAnimationEndAction: () -> Unit) {
        if (diceViews.isEmpty()) {
            onAnimationEndAction()
            return
        }
        var animationsPending = diceViews.size
        diceViews.forEach { dieView ->
            dieView.alpha = 0f // Start transparent for fade-in effect
            val rotation = ObjectAnimator.ofFloat(dieView, "rotation", 0f, 360f * (Random.nextInt(2, 5)))
            rotation.duration = animationDuration
            rotation.interpolator = AccelerateDecelerateInterpolator()

            val fadeIn = ObjectAnimator.ofFloat(dieView, "alpha", 0f, 1f)
            fadeIn.duration = animationDuration / 4 // Faster fade-in

            rotation.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animationsPending--
                    if (animationsPending == 0) {
                        onAnimationEndAction()
                    }
                }
            })
            rotation.start()
            fadeIn.start()
        }
    }

    @DrawableRes
    private fun getDieResultDrawable(sides: Int, rollValue: Int): Int {
        // Make sure it correctly handles DicePoolDialogFragment.D10_TENS_KEY and D10_UNITS_KEY.
        val usePipsForD6 = viewModel.settings.value?.useDicePips ?: false
        val resourceName = when (sides) {
            4 -> "d4_result_$rollValue"
            6 -> if (usePipsForD6) "d6_result_${rollValue}p" else "d6_result_$rollValue"
            8 -> "d8_result_$rollValue"
            10 -> "d10_result_$rollValue" // Standard D10 (0-9 or 1-10)
            DicePoolDialogFragment.D10_TENS_KEY -> "d10p_result_$rollValue" // Percentile tens (00-90)
            DicePoolDialogFragment.D10_UNITS_KEY -> "d10_result_$rollValue" // Percentile units (0-9)
            12 -> "d12_result_$rollValue"
            20 -> "d20_result_$rollValue"
            else -> "ic_die_placeholder" // Fallback
        }
        val currentContext = context ?: return R.drawable.ic_die_placeholder
        val resourceId = currentContext.resources.getIdentifier(resourceName, "drawable", currentContext.packageName)

        return if (resourceId != 0) resourceId else {
            Log.w("DiceMainFragment", "Drawable not found for $resourceName, using placeholder.")
            R.drawable.ic_die_placeholder
        }
    }

    // *** Dice Pool Results Display ***
    private fun displayDicePoolResults(results: DiceRollResults?) {
        // If animation was running, the temporary views are already there.
        // We need to update them if settings.isDiceAnimationEnabled is true.
        // Otherwise, we build from scratch.
        if (viewModel.settings.value?.isDiceAnimationEnabled == true && binding.diceDisplayArea.childCount > 0 && lastResultType == LastResultType.POOL) {
            updateAnimatedDiceGroupsWithResults(results)
            return
        }

        binding.diceDisplayArea.removeAllViews()
        if (results == null) return

        val uniqueDiceTypes = results.entries.filter { it.value.isNotEmpty() }.sortedBy { it.key }
        val numUniqueTypes = uniqueDiceTypes.size

        if (numUniqueTypes == 0 && lastResultType == LastResultType.POOL) {
            val textView = TextView(requireContext()).apply { text = getString(R.string.dice_no_dice_in_pool) }
            binding.diceDisplayArea.addView(textView)
            // Reset to default flexbox settings if no dice
            binding.diceDisplayArea.justifyContent = JustifyContent.CENTER
            binding.diceDisplayArea.alignItems = AlignItems.FLEX_START // Or CENTER if preferred for single message
            binding.diceDisplayArea.flexDirection = FlexDirection.ROW
            binding.diceDisplayArea.flexWrap = FlexWrap.WRAP
            return
        }

        applyFlexboxSettingsForDiceGroups(numUniqueTypes)

        val inflater = LayoutInflater.from(context)
        val colorConfig = parseDiceColorConfig(viewModel.settings.value?.diceColorConfigJson)

        uniqueDiceTypes.forEach { (sides, rolls) ->
            val dieTypeGroup = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                // Add some padding/margin to the group itself if needed for spacing between groups
                val groupParams = FlexboxLayout.LayoutParams(
                    FlexboxLayout.LayoutParams.WRAP_CONTENT,
                    FlexboxLayout.LayoutParams.WRAP_CONTENT
                )
                // Example: Add some margin around each group
                // groupParams.setMargins(10, 10, 10, 10) // (left, top, right, bottom in pixels)
                layoutParams = groupParams
            }

            rolls.forEach { rollValue ->
                val itemBinding = ItemDieResultBinding.inflate(inflater, dieTypeGroup, false)
                itemBinding.dieResultImageView.setImageResource(getDieResultDrawable(sides, rollValue))
                applyTint(itemBinding.dieResultImageView, colorConfig[sides])
                // Add some margin between individual dice in a group if desired
                // val dieParams = itemBinding.root.layoutParams as LinearLayout.LayoutParams
                // dieParams.marginEnd = 8 // pixels
                // itemBinding.root.layoutParams = dieParams
                dieTypeGroup.addView(itemBinding.root)
            }
            binding.diceDisplayArea.addView(dieTypeGroup)
        }
    }

    private fun applyFlexboxSettingsForDiceGroups(numUniqueTypes: Int) {
        binding.diceDisplayArea.apply {
            when (numUniqueTypes) {
                0 -> { // No dice or only empty types
                    justifyContent = JustifyContent.CENTER
                    alignItems = AlignItems.CENTER
                    flexDirection = FlexDirection.ROW
                    flexWrap = FlexWrap.NO_WRAP // Or WRAP if the "no dice" message could wrap
                }
                1 -> {
                    justifyContent = JustifyContent.CENTER
                    alignItems = AlignItems.CENTER
                    flexDirection = FlexDirection.ROW // The single group can flow internally
                    flexWrap = FlexWrap.NO_WRAP // The group itself shouldn't wrap relative to Flexbox parent
                }
                2 -> {
                    justifyContent = JustifyContent.SPACE_AROUND
                    alignItems = AlignItems.CENTER
                    flexDirection = FlexDirection.ROW
                    flexWrap = FlexWrap.NO_WRAP // Two groups side-by-side
                }
                3 -> { // Attempt at a triangle: 1 on top, 2 below
                    justifyContent = JustifyContent.CENTER
                    alignItems = AlignItems.CENTER
                    flexDirection = FlexDirection.ROW // Let flexWrap handle rows
                    flexWrap = FlexWrap.WRAP
                    // This is an approximation. The first child (group) might need specific
                    // FlexboxLayout.LayoutParams to encourage it to be on its own line,
                    // e.g., layout_wrapBefore = true (if set on the second item's group).
                    // Or by making the first item take more basis.
                    // For a true triangle, ConstraintLayout is better.
                    // We can try to enforce it by setting flexBasisPercent on the children.
                    // The first child (index 0) takes full width to be on its own row.
                    // The next two (index 1, 2) share the next row.
                    // This requires iterating children after they are added, or storing groups first.
                }
                4 -> { // Square: 2x2
                    justifyContent = JustifyContent.CENTER // Or SPACE_AROUND for outer spacing
                    alignItems = AlignItems.CENTER     // Align items within a line
                    alignContent = AlignContent.CENTER   // Align lines themselves (for multi-line)
                    flexDirection = FlexDirection.ROW
                    flexWrap = FlexWrap.WRAP
                    // Children (groups) will need layout_flexBasisPercent near 50%
                }
                else -> { // 5+ types
                    justifyContent = JustifyContent.SPACE_AROUND // Default for many items
                    alignItems = AlignItems.CENTER
                    alignContent = AlignContent.SPACE_AROUND
                    flexDirection = FlexDirection.ROW
                    flexWrap = FlexWrap.WRAP
                    // For precise pentagons etc., Flexbox is limited. Consider ConstraintLayout.
                    Log.i("DiceMainFragment", "For $numUniqueTypes unique dice types, a precise polygonal layout is complex with Flexbox. Using a wrapped row layout.")
                }
            }
        }
        // Post-add adjustments for 3 and 4 types for better geometric layout
        if (numUniqueTypes == 3 || numUniqueTypes == 4) {
            for (i in 0 until binding.diceDisplayArea.childCount) {
                val groupView = binding.diceDisplayArea.getChildAt(i)
                val params = groupView.layoutParams as FlexboxLayout.LayoutParams
                if (numUniqueTypes == 3) {
                    if (i == 0) { // Top of triangle
                        params.flexBasisPercent = 1.0f // Full width for the first item's line
                        params.isWrapBefore = false // First item doesn't wrap before
                    } else { // Bottom two items
                        params.flexBasisPercent = 0.45f // Share the next line (less than 0.5 for spacing)
                        if (i == 1) params.isWrapBefore = true // Second item starts a new line
                    }
                } else if (numUniqueTypes == 4) { // 2x2
                    params.flexBasisPercent = 0.45f // Two items per line (less than 0.5 for spacing)
                    if (i == 2) params.isWrapBefore = true // Third item starts a new "row" for the 2x2
                }
                groupView.layoutParams = params
            }
        }
    }

    private fun displayPercentileResult(processedResult: ProcessedDiceResult?) {
        if (viewModel.settings.value?.isDiceAnimationEnabled == true && binding.diceDisplayArea.childCount > 0 && lastResultType == LastResultType.PERCENTILE) {
            updateAnimatedViewsWithPercentileResult(processedResult)
            return
        }

        binding.diceDisplayArea.removeAllViews()
        // Ensure Flexbox settings are appropriate for percentile display (usually 2 dice + text)
        binding.diceDisplayArea.justifyContent = JustifyContent.CENTER
        binding.diceDisplayArea.alignItems = AlignItems.CENTER
        binding.diceDisplayArea.flexDirection = FlexDirection.ROW
        binding.diceDisplayArea.flexWrap = FlexWrap.NO_WRAP


        if (processedResult?.isPercentile != true || processedResult.percentileValue == null) return

        val finalSum = processedResult.percentileValue
        val componentRolls = processedResult.rawRolls

        val tensValueForDisplay = componentRolls?.get(DicePoolDialogFragment.D10_TENS_KEY)?.firstOrNull() ?: 0
        val unitsValueForDisplay = componentRolls?.get(DicePoolDialogFragment.D10_UNITS_KEY)?.firstOrNull() ?: 0

        val inflater = LayoutInflater.from(context)
        val colorConfig = parseDiceColorConfig(viewModel.settings.value?.diceColorConfigJson)

        // Create a single LinearLayout group for percentile dice + result text
        val percentileGroup = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL // Align items vertically in the group
            layoutParams = FlexboxLayout.LayoutParams(
                FlexboxLayout.LayoutParams.WRAP_CONTENT,
                FlexboxLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tensBinding = ItemDieResultBinding.inflate(inflater, percentileGroup, false)
        tensBinding.dieResultImageView.setImageResource(getDieResultDrawable(DicePoolDialogFragment.D10_TENS_KEY, tensValueForDisplay))
        applyTint(tensBinding.dieResultImageView, colorConfig[DicePoolDialogFragment.D10_TENS_KEY])
        percentileGroup.addView(tensBinding.root)

        val unitsBinding = ItemDieResultBinding.inflate(inflater, percentileGroup, false)
        unitsBinding.dieResultImageView.setImageResource(getDieResultDrawable(DicePoolDialogFragment.D10_UNITS_KEY, unitsValueForDisplay))
        applyTint(unitsBinding.dieResultImageView, colorConfig[DicePoolDialogFragment.D10_UNITS_KEY])
        // Add margin to the units die if needed for spacing
        (unitsBinding.root.layoutParams as? LinearLayout.LayoutParams)?.marginStart = 8 // pixels
        percentileGroup.addView(unitsBinding.root)

        val resultTextView = TextView(requireContext()).apply {
            text = "= $finalSum%"
            textSize = 24f // Or from dimens
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.marginStart = 16 // pixels (or from dimens)
            layoutParams = params
        }
        percentileGroup.addView(resultTextView)
        binding.diceDisplayArea.addView(percentileGroup)
    }

    // Modified to update dice within their groups
    private fun updateAnimatedDiceGroupsWithResults(results: DiceRollResults?) {
        if (results == null) {
            binding.diceDisplayArea.removeAllViews() // Or handle gracefully
            return
        }

        val uniqueDiceTypesFromResults = results.entries.filter { it.value.isNotEmpty() }.sortedBy { it.key }
        val colorConfig = parseDiceColorConfig(viewModel.settings.value?.diceColorConfigJson)
        var groupViewIndex = 0

        uniqueDiceTypesFromResults.forEach { (sides, rolls) ->
            if (groupViewIndex < binding.diceDisplayArea.childCount) {
                val groupView = binding.diceDisplayArea.getChildAt(groupViewIndex) as? ViewGroup ?: return@forEach
                var dieInGroupIndex = 0
                rolls.forEach { rollValue ->
                    if (dieInGroupIndex < groupView.childCount) {
                        val itemView = groupView.getChildAt(dieInGroupIndex)
                        val imageView = itemView.findViewById<ImageView>(R.id.dieResultImageView)
                        imageView?.setImageResource(getDieResultDrawable(sides, rollValue))
                        applyTint(imageView, colorConfig[sides])
                        imageView?.alpha = 1f
                        imageView?.rotation = 0f // Reset animation artifacts
                    }
                    dieInGroupIndex++
                }
                // Remove extra dice views from this group if pool shrunk
                while (groupView.childCount > dieInGroupIndex) {
                    groupView.removeViewAt(dieInGroupIndex)
                }
            }
            groupViewIndex++
        }

        // Remove extra group views if number of unique types shrunk
        while (binding.diceDisplayArea.childCount > groupViewIndex) {
            binding.diceDisplayArea.removeViewAt(groupViewIndex)
        }
        // Re-apply flexbox settings in case the number of groups changed significantly
        applyFlexboxSettingsForDiceGroups(groupViewIndex)
    }


    private fun updateAnimatedViewsWithPercentileResult(processedResult: ProcessedDiceResult?) {
        val finalSum = processedResult?.percentileValue
        if (finalSum == null) {
            binding.diceDisplayArea.removeAllViews()
            return
        }

        // Assuming percentile results are always in one group view at index 0
        if (binding.diceDisplayArea.childCount == 0) {
            // Animation finished but view was cleared, fallback to full display
            displayPercentileResult(processedResult)
            return
        }
        val percentileGroup = binding.diceDisplayArea.getChildAt(0) as? ViewGroup
        if (percentileGroup == null || percentileGroup.childCount < 3) { // Expect 2 dice ImageViews + 1 TextView
            displayPercentileResult(processedResult) // Fallback if structure is not as expected
            return
        }

        val componentRolls = processedResult.rawRolls
        val tensValueForDisplay = componentRolls?.get(DicePoolDialogFragment.D10_TENS_KEY)?.firstOrNull() ?: 0
        val unitsValueForDisplay = componentRolls?.get(DicePoolDialogFragment.D10_UNITS_KEY)?.firstOrNull() ?: 0
        val colorConfig = parseDiceColorConfig(viewModel.settings.value?.diceColorConfigJson)

        (percentileGroup.getChildAt(0)?.findViewById<ImageView>(R.id.dieResultImageView))?.apply {
            setImageResource(getDieResultDrawable(DicePoolDialogFragment.D10_TENS_KEY, tensValueForDisplay))
            applyTint(this, colorConfig[DicePoolDialogFragment.D10_TENS_KEY])
            alpha = 1f; rotation = 0f
        }
        (percentileGroup.getChildAt(1)?.findViewById<ImageView>(R.id.dieResultImageView))?.apply {
            setImageResource(getDieResultDrawable(DicePoolDialogFragment.D10_UNITS_KEY, unitsValueForDisplay))
            applyTint(this, colorConfig[DicePoolDialogFragment.D10_UNITS_KEY])
            alpha = 1f; rotation = 0f
        }
        (percentileGroup.getChildAt(2) as? TextView)?.text = "= $finalSum%"
    }

    private fun applyTint(imageView: ImageView?, colorInt: Int?) {
        imageView?.let {
            // Use a default color from PurramidPalette if colorInt is null
            val tintToApply = colorInt ?: PurramidPalette.DEFAULT_DIE_COLOR.colorInt
            if (tintToApply != Color.TRANSPARENT) { // Assuming TRANSPARENT means 'no tint' or 'use default SVG colors'
                it.colorFilter = PorterDuffColorFilter(tintToApply, PorterDuff.Mode.SRC_IN)
            } else {
                it.clearColorFilter()
            }
        }
    }

    private fun parseDiceColorConfig(json: String?): Map<Int, Int> {
        if (json.isNullOrEmpty() || json == DEFAULT_EMPTY_JSON_MAP) {
            return emptyMap()
        }
        return try {
            val mapType = object : TypeToken<Map<Int, Int>>() {}.type
            gson.fromJson(json, mapType)
        } catch (e: Exception) {
            Log.e("DiceMainFragment", "Failed to parse dice color config JSON: $json", e)
            emptyMap()
        }
    }

    private fun navigateToSettings(instanceId: Int) {
        try {
            val action = DiceMainFragmentDirections.actionDiceMainFragmentToRandomizerSettingsFragment(instanceId)
            findNavController().navigate(action)
        } catch (e: Exception) {
            Log.e("DiceMainFragment", "Navigation to Settings failed.", e)
        }
    }

    private fun showErrorSnackbar(message: String) {
        view?.let { // Ensure view is available
            Snackbar.make(it, message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun startCritCelebration() {
        if (_binding == null) return

        val accessibilityManager = context?.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (accessibilityManager?.isTouchExplorationEnabled == true) {
            Log.d("DiceMainFragment", "Reduced motion enabled, skipping confetti.")
            return
        }

        val partyColors = PurramidPalette.CONFETTI_COLORS.map { it.colorInt } // Assuming PurramidPalette.CONFETTI_COLORS is List<PurramidColor>

        binding.konfettiViewDice.start(
            Party(
                speed = 0f,
                maxSpeed = 35f,
                damping = 0.9f,
                spread = 360,
                colors = partyColors.ifEmpty { listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED) }, // Fallback colors
                emitter = Emitter(duration = 150, TimeUnit.MILLISECONDS).max(150),
                position = Position.Relative(0.5, 0.3)
            )
        )
    }
    // TODO: Consider adding a "Free Form" mode to Dice, similar to Coin Flip.
    // This would allow users to drag dice around the screen and potentially
    // manually set their faces if that aligns with the desired functionality.
    // This would be a post-launch enhancement after current Coin Flip work.
}