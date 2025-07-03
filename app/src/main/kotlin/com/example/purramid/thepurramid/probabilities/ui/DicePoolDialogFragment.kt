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
import com.example.purramid.thepurramid.probabilities.viewmodel.DiceViewModel
import com.example.purramid.thepurramid.probabilities.viewmodel.DieType
import com.example.purramid.thepurramid.R

class DicePoolDialogFragment : DialogFragment() {
    private val diceViewModel: DiceViewModel by activityViewModels()
    private val pickers = mutableMapOf<DieType, NumberPicker>()
    private val colorButtons = mutableMapOf<DieType, Button>()
    private val colors = mutableMapOf<DieType, Int>()
    private val modifierPickers = mutableMapOf<DieType, NumberPicker>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        for (type in DieType.values()) {
            val picker = NumberPicker(requireContext()).apply {
                minValue = 0
                maxValue = 10
                value = diceViewModel.settings.value?.dieConfigs?.find { it.type == type }?.quantity ?: 1
            }
            pickers[type] = picker
            val label = Button(requireContext()).apply {
                text = type.name
                isEnabled = false
            }
            val color = diceViewModel.settings.value?.dieConfigs?.find { it.type == type }?.color ?: Color.LTGRAY
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
            val modifierPicker = NumberPicker(requireContext()).apply {
                minValue = -10
                maxValue = 10
                value = diceViewModel.settings.value?.dieConfigs?.find { it.type == type }?.modifier ?: 0
            }
            modifierPickers[type] = modifierPicker
            layout.addView(label)
            layout.addView(picker)
            layout.addView(colorButton)
            layout.addView(modifierPicker)
        }
        val saveButton = Button(requireContext()).apply { text = "Save" }
        val cancelButton = Button(requireContext()).apply { text = "Cancel" }
        layout.addView(saveButton)
        layout.addView(cancelButton)
        saveButton.setOnClickListener {
            for (type in DieType.values()) {
                diceViewModel.updateDieConfig(requireContext(), type, pickers[type]?.value, colors[type], modifierPickers[type]?.value)
            }
            dismiss()
        }
        cancelButton.setOnClickListener { dismiss() }
        return layout
    }
} 