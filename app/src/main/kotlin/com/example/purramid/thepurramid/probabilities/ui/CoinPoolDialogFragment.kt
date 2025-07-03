package com.example.purramid.thepurramid.probabilities.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.example.purramid.thepurramid.probabilities.viewmodel.CoinFlipViewModel
import com.example.purramid.thepurramid.probabilities.viewmodel.CoinType
import com.example.purramid.thepurramid.R

class CoinPoolDialogFragment : DialogFragment() {
    private val coinFlipViewModel: CoinFlipViewModel by activityViewModels()
    private val pickers = mutableMapOf<CoinType, NumberPicker>()
    private val colorButtons = mutableMapOf<CoinType, Button>()
    private val colors = mutableMapOf<CoinType, Int>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        for (type in CoinType.values()) {
            val picker = NumberPicker(requireContext()).apply {
                minValue = 0
                maxValue = 10
                value = coinFlipViewModel.settings.value?.coinConfigs?.find { it.type == type }?.quantity ?: 1
            }
            pickers[type] = picker
            val label = Button(requireContext()).apply {
                text = type.name
                isEnabled = false
            }
            val color = coinFlipViewModel.settings.value?.coinConfigs?.find { it.type == type }?.color ?: Color.LTGRAY
            colors[type] = color
            val colorButton = Button(requireContext()).apply {
                text = "Pick Color"
                setBackgroundColor(color)
                setOnClickListener {
                    // Stub: cycle through a few colors for demo
                    val nextColor = when (colors[type]) {
                        Color.LTGRAY -> Color.YELLOW
                        Color.YELLOW -> Color.CYAN
                        Color.CYAN -> Color.MAGENTA
                        else -> Color.LTGRAY
                    }
                    colors[type] = nextColor
                    setBackgroundColor(nextColor)
                }
            }
            colorButtons[type] = colorButton
            layout.addView(label)
            layout.addView(picker)
            layout.addView(colorButton)
        }
        val saveButton = Button(requireContext()).apply { text = "Save" }
        val cancelButton = Button(requireContext()).apply { text = "Cancel" }
        layout.addView(saveButton)
        layout.addView(cancelButton)
        saveButton.setOnClickListener {
            for (type in CoinType.values()) {
                coinFlipViewModel.updateCoinConfig(requireContext(), type, pickers[type]?.value, colors[type])
            }
            dismiss()
        }
        cancelButton.setOnClickListener { dismiss() }
        return layout
    }
} 