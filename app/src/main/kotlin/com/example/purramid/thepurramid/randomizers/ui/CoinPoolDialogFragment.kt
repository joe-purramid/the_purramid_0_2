// CoinPoolDialogFragment.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.DialogCoinPoolBinding
import com.example.purramid.thepurramid.databinding.IncludeCoinPoolRowBinding
import com.example.purramid.thepurramid.randomizers.viewmodel.CoinFlipViewModel
import com.example.purramid.thepurramid.randomizers.viewmodel.CoinInPool
import com.example.purramid.thepurramid.randomizers.viewmodel.CoinType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID

@AndroidEntryPoint
class CoinPoolDialogFragment : DialogFragment() {

    private var _binding: DialogCoinPoolBinding? = null
    private val binding get() = _binding!!

    private val coinFlipViewModel: CoinFlipViewModel by activityViewModels()

    private data class CoinRowViews(
        val rowBinding: IncludeCoinPoolRowBinding,
        val coinType: CoinType
    )
    private lateinit var coinRowViewMap: Map<CoinType, CoinRowViews>

    // Flag to prevent TextWatcher loops
    private var isUpdatingEditTextProgrammatically = false

    companion object {
        const val TAG = "CoinPoolDialogFragment"

        fun newInstance(instanceId: Int?): CoinPoolDialogFragment {
            return CoinPoolDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt("instanceId", instanceId ?: 0)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogCoinPoolBinding.inflate(LayoutInflater.from(context))

        initializeCoinRowViews()
        setupListeners()
        observeViewModelState()

        return AlertDialog.Builder(requireActivity())
            .setView(binding.root)
            // Title is set in XML, buttons handled by layout
            .create()
    }

    private fun initializeCoinRowViews() {
        coinRowViewMap = mapOf(
            CoinType.BIT_1 to CoinRowViews(IncludeCoinPoolRowBinding.bind(binding.coinRow1Bit.root), CoinType.BIT_1),
            CoinType.BIT_5 to CoinRowViews(IncludeCoinPoolRowBinding.bind(binding.coinRow5Bits.root), CoinType.BIT_5),
            CoinType.BIT_10 to CoinRowViews(IncludeCoinPoolRowBinding.bind(binding.coinRow10Bits.root), CoinType.BIT_10),
            CoinType.BIT_25 to CoinRowViews(IncludeCoinPoolRowBinding.bind(binding.coinRow25Bits.root), CoinType.BIT_25),
            CoinType.MEATBALL_1 to CoinRowViews(IncludeCoinPoolRowBinding.bind(binding.coinRow1Meatball.root), CoinType.MEATBALL_1),
            CoinType.MEATBALL_2 to CoinRowViews(IncludeCoinPoolRowBinding.bind(binding.coinRow2Meatballs.root), CoinType.MEATBALL_2)
        )
    }

    private fun setupListeners() {
        binding.coinPoolCloseButton.setOnClickListener {
            dismiss()
        }

        coinRowViewMap.forEach { (type, views) ->
            val coinTypeLabel = getCoinLabel(type)
            views.rowBinding.coinTypeLabel.text = coinTypeLabel
            views.rowBinding.coinTypeIcon.setImageResource(getCoinIconRes(type)) // Use obverse for icon

            views.rowBinding.decrementButton.setOnClickListener {
                coinFlipViewModel.removeCoinFromPool(type)
            }
            views.rowBinding.incrementButton.setOnClickListener {
                coinFlipViewModel.addCoinToPool(type)
            }

            views.rowBinding.coinCountEditText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isUpdatingEditTextProgrammatically || !views.rowBinding.coinCountEditText.hasFocus()) {
                        return
                    }
                    val newCount = s.toString().toIntOrNull() ?: 0
                    val currentPool = coinFlipViewModel.uiState.value.coinPool
                    val currentCountForType = currentPool.count { it.type == type }

                    // Adjust pool to match newCount
                    when {
                        newCount > currentCountForType -> {
                            repeat( (newCount - currentCountForType).coerceAtMost(CoinFlipViewModel.MAX_COINS_PER_TYPE - currentCountForType) ) {
                                coinFlipViewModel.addCoinToPool(type)
                            }
                        }
                        newCount < currentCountForType -> {
                            repeat(currentCountForType - newCount) {
                                coinFlipViewModel.removeCoinFromPool(type)
                            }
                        }
                    }
                    // The observer will update the EditText if the actual count changed due to MAX_COINS_PER_TYPE limit
                }
            })
        }
    }

    private fun observeViewModelState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                coinFlipViewModel.uiState.collect { state ->
                    updateCoinCountsInDialog(state.coinPool)
                }
            }
        }
    }

    private fun updateCoinCountsInDialog(pool: List<CoinInPool>) {
        isUpdatingEditTextProgrammatically = true
        coinRowViewMap.forEach { (type, views) ->
            val count = pool.count { it.type == type }
            if (views.rowBinding.coinCountEditText.text.toString() != count.toString()) {
                views.rowBinding.coinCountEditText.setText(count.toString())
            }
             // Update button states based on count
            views.rowBinding.decrementButton.isEnabled = count > 0
            views.rowBinding.incrementButton.isEnabled = count < CoinFlipViewModel.MAX_COINS_PER_TYPE
        }
        isUpdatingEditTextProgrammatically = false
    }

    private fun getCoinLabel(coinType: CoinType): String {
        return when (coinType) {
            CoinType.BIT_1 -> getString(R.string.label_1_bit)
            CoinType.BIT_5 -> getString(R.string.label_5_bits)
            CoinType.BIT_10 -> getString(R.string.label_10_bits)
            CoinType.BIT_25 -> getString(R.string.label_25_bits)
            CoinType.MEATBALL_1 -> getString(R.string.label_1_meatball)
            CoinType.MEATBALL_2 -> getString(R.string.label_2_meatballs)
        }
    }

    private fun getCoinIconRes(coinType: CoinType): Int {
        // Return the obverse (heads) for the icon
        return when (coinType) {
            CoinType.BIT_1 -> R.drawable.b1_coin_flip_heads // Replace with actual when available
            CoinType.BIT_5 -> R.drawable.b5_coin_flip_heads
            CoinType.BIT_10 -> R.drawable.b10_coin_flip_heads
            CoinType.BIT_25 -> R.drawable.b25_coin_flip_heads
            CoinType.MEATBALL_1 -> R.drawable.mb1_coin_flip_heads
            CoinType.MEATBALL_2 -> R.drawable.mb2_coin_flip_heads
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}