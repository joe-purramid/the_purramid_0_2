package com.example.purramid.thepurramid.probabilities.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.probabilities.ProbabilitiesMode
import com.example.purramid.thepurramid.probabilities.viewmodel.*
import com.google.android.material.button.MaterialButtonToggleGroup

class ProbabilitiesSettingsFragment : Fragment() {
    private val settingsViewModel: ProbabilitiesSettingsViewModel by activityViewModels()
    private val diceViewModel: DiceViewModel by activityViewModels()
    private val coinFlipViewModel: CoinFlipViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_probabilities_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val modeToggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.modeToggleGroup)
        val diceSettingsGroup = view.findViewById<LinearLayout>(R.id.diceSettingsGroup)
        val coinSettingsGroup = view.findViewById<LinearLayout>(R.id.coinSettingsGroup)
        val buttonModeDice = view.findViewById<View>(R.id.buttonModeDice)
        val buttonModeCoinFlip = view.findViewById<View>(R.id.buttonModeCoinFlip)

        val switchCriticalSuccess = view.findViewById<Switch>(R.id.switchCriticalSuccess)
        val switchPercentileDice = view.findViewById<Switch>(R.id.switchPercentileDice)
        val switchDiceGraph = view.findViewById<Switch>(R.id.switchDiceGraph)
        val spinnerDiceGraphType = view.findViewById<Spinner>(R.id.spinnerDiceGraphType)
        val spinnerDiceDistribution = view.findViewById<Spinner>(R.id.spinnerDiceDistribution)
        val buttonEditDicePool = view.findViewById<Button>(R.id.buttonEditDicePool)

        val switchProbabilityMode = view.findViewById<Switch>(R.id.switchProbabilityMode)
        val spinnerProbabilityType = view.findViewById<Spinner>(R.id.spinnerProbabilityType)
        val switchCoinGraph = view.findViewById<Switch>(R.id.switchCoinGraph)
        val spinnerCoinGraphType = view.findViewById<Spinner>(R.id.spinnerCoinGraphType)
        val spinnerCoinDistribution = view.findViewById<Spinner>(R.id.spinnerCoinDistribution)
        val switchAnnounce = view.findViewById<Switch>(R.id.switchAnnounce)
        val switchFreeForm = view.findViewById<Switch>(R.id.switchFreeForm)
        val buttonEditCoinPool = view.findViewById<Button>(R.id.buttonEditCoinPool)

        val instanceId = arguments?.getInt("instanceId") ?: 1

        // Populate spinners
        val graphTypes = listOf("Histogram", "Line", "Q-Q")
        val distributions = listOf("Normal", "Uniform")
        val probabilityTypes = listOf("Two Column", "3x3", "6x6", "10x10")
        spinnerDiceGraphType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, graphTypes)
        spinnerDiceDistribution.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, distributions)
        spinnerCoinGraphType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, graphTypes)
        spinnerCoinDistribution.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, distributions)
        spinnerProbabilityType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, probabilityTypes)

        // Show/hide settings group based on mode
        settingsViewModel.settings.observe(viewLifecycleOwner) { settings ->
            if (settings != null) {
                when (settings.mode) {
                    ProbabilitiesMode.DICE -> {
                        diceSettingsGroup.visibility = View.VISIBLE
                        coinSettingsGroup.visibility = View.GONE
                    }
                    ProbabilitiesMode.COIN_FLIP -> {
                        diceSettingsGroup.visibility = View.GONE
                        coinSettingsGroup.visibility = View.VISIBLE
                    }
                }
            }
        }

        // Mode toggle group
        modeToggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.buttonModeDice -> settingsViewModel.updateMode(ProbabilitiesMode.DICE)
                    R.id.buttonModeCoinFlip -> settingsViewModel.updateMode(ProbabilitiesMode.COIN_FLIP)
                }
            }
        }

        // Dice settings listeners
        switchCriticalSuccess.setOnCheckedChangeListener { _, isChecked ->
            diceViewModel.updateSettings(requireContext(), critEnabled = isChecked)
        }
        switchPercentileDice.setOnCheckedChangeListener { _, isChecked ->
            diceViewModel.updateSettings(requireContext(), usePercentile = isChecked)
        }
        switchDiceGraph.setOnCheckedChangeListener { _, isChecked ->
            diceViewModel.updateSettings(requireContext(), graphEnabled = isChecked)
        }
        spinnerDiceGraphType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                diceViewModel.updateSettings(requireContext(), graphType = graphTypes[position].lowercase())
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        spinnerDiceDistribution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                diceViewModel.updateSettings(requireContext(), graphDistribution = distributions[position].lowercase())
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        buttonEditDicePool.setOnClickListener {
            DicePoolDialogFragment().show(childFragmentManager, "DicePoolDialog")
        }

        // Coin settings listeners
        switchProbabilityMode.setOnCheckedChangeListener { _, isChecked ->
            coinFlipViewModel.updateSettings(requireContext(), probabilityEnabled = isChecked)
        }
        spinnerProbabilityType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                coinFlipViewModel.updateSettings(requireContext(), probabilityType = probabilityTypes[position].lowercase().replace(" ", "_"))
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        switchCoinGraph.setOnCheckedChangeListener { _, isChecked ->
            coinFlipViewModel.updateSettings(requireContext(), graphEnabled = isChecked)
        }
        spinnerCoinGraphType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                coinFlipViewModel.updateSettings(requireContext(), graphType = graphTypes[position].lowercase())
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        spinnerCoinDistribution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                coinFlipViewModel.updateSettings(requireContext(), graphDistribution = distributions[position].lowercase())
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        switchAnnounce.setOnCheckedChangeListener { _, isChecked ->
            coinFlipViewModel.updateSettings(requireContext(), announce = isChecked)
        }
        switchFreeForm.setOnCheckedChangeListener { _, isChecked ->
            coinFlipViewModel.updateSettings(requireContext(), freeForm = isChecked)
        }
        buttonEditCoinPool.setOnClickListener {
            CoinPoolDialogFragment().show(childFragmentManager, "CoinPoolDialog")
        }
    }
} 