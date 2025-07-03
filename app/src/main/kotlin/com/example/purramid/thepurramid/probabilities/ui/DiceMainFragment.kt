package com.example.purramid.thepurramid.probabilities.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.probabilities.viewmodel.DiceViewModel
import com.example.purramid.thepurramid.probabilities.viewmodel.DieType
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter

class DiceMainFragment : Fragment() {
    private val diceViewModel: DiceViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dice_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rollButton = view.findViewById<Button>(R.id.buttonRoll)
        val resetButton = view.findViewById<Button>(R.id.buttonReset)
        val resultText = view.findViewById<TextView>(R.id.textDiceResult)
        val barChart = view.findViewById<BarChart>(R.id.diceBarChart)

        // Theme-aware colors
        val isDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val barColor = ContextCompat.getColor(requireContext(), if (isDark) R.color.teal_200 else R.color.purple_500)
        val axisTextColor = ContextCompat.getColor(requireContext(), if (isDark) android.R.color.white else android.R.color.darker_gray)
        val valueTextColor = axisTextColor

        // Chart styling
        barChart.setDrawGridBackground(false)
        barChart.setDrawBarShadow(false)
        barChart.description.isEnabled = false
        barChart.legend.isEnabled = true
        barChart.legend.textSize = 12f
        barChart.legend.form = com.github.mikephil.charting.components.Legend.LegendForm.SQUARE
        barChart.legend.textColor = axisTextColor
        barChart.setNoDataText("No data yet. Roll the dice!")
        barChart.setTouchEnabled(false)
        barChart.setScaleEnabled(false)
        barChart.setPinchZoom(false)
        barChart.axisRight.isEnabled = false
        barChart.axisLeft.textColor = axisTextColor
        barChart.xAxis.textColor = axisTextColor
        barChart.axisLeft.setDrawGridLines(false)
        barChart.xAxis.setDrawGridLines(false)
        barChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        barChart.xAxis.granularity = 1f
        barChart.xAxis.labelRotationAngle = 0f
        barChart.axisLeft.axisMinimum = 0f
        barChart.axisLeft.granularity = 1f
        barChart.setExtraOffsets(0f, 16f, 0f, 16f)

        val updateGraph = {
            val settings = diceViewModel.settings.value
            val result = diceViewModel.result.value
            if (settings?.graphEnabled == true && result != null) {
                barChart.visibility = View.VISIBLE
                val freq = mutableMapOf<Int, Int>()
                for (rolls in result.results.values) {
                    for (roll in rolls) freq[roll] = (freq[roll] ?: 0) + 1
                }
                val sorted = freq.entries.sortedBy { it.key }
                val entries = sorted.mapIndexed { idx, (value, count) ->
                    BarEntry(idx.toFloat(), count.toFloat())
                }
                val dataSet = BarDataSet(entries, "Dice Results")
                dataSet.color = barColor
                dataSet.valueTextColor = valueTextColor
                dataSet.valueTextSize = 14f
                dataSet.valueFormatter = object : ValueFormatter() {
                    override fun getBarLabel(barEntry: BarEntry?): String {
                        return barEntry?.y?.toInt()?.toString() ?: ""
                    }
                }
                val barData = BarData(dataSet)
                barData.barWidth = 0.8f
                barChart.data = barData
                barChart.xAxis.valueFormatter = IndexAxisValueFormatter(sorted.map { it.key.toString() })
                barChart.xAxis.labelCount = sorted.size
                barChart.animateY(700)
                barChart.invalidate()
            } else {
                barChart.visibility = View.GONE
            }
        }

        rollButton?.setOnClickListener { diceViewModel.rollDice() }
        resetButton?.setOnClickListener { diceViewModel.reset() }

        diceViewModel.result.observe(viewLifecycleOwner) { result ->
            resultText?.text = result?.results?.entries?.joinToString("\n") { (type, rolls) ->
                "${type.name}: ${rolls.joinToString(", ")}"
            } ?: ""
            updateGraph()
        }
        diceViewModel.settings.observe(viewLifecycleOwner) { updateGraph() }
    }
} 