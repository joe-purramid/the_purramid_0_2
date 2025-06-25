// ScreenMaskSettingsFragment.kt
package com.example.purramid.thepurramid.screen_mask.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
// Removed unused activityViewModels and other lifecycle imports for this simpler version
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.FragmentScreenMaskSettingsBinding
import com.example.purramid.thepurramid.screen_mask.ACTION_ADD_NEW_MASK_INSTANCE
import com.example.purramid.thepurramid.screen_mask.ScreenMaskActivity // For PREFS_NAME, KEY_ACTIVE_COUNT
import com.example.purramid.thepurramid.screen_mask.ScreenMaskService
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ScreenMaskSettingsFragment : Fragment() {

    private var _binding: FragmentScreenMaskSettingsBinding? = null
    private val binding get() = _binding!!

    // No ViewModel needed for this simplified settings fragment if it only adds masks
    // and doesn't manage default color/opacity preferences.

    companion object {
        const val TAG = "ScreenMaskSettingsFragment"
        private const val ARG_INSTANCE_ID = "instance_id"

        fun newInstance(instanceId: Int): ScreenMaskSettingsFragment {
            return ScreenMaskSettingsFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_INSTANCE_ID, instanceId)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScreenMaskSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val instanceId = arguments?.getInt(ARG_INSTANCE_ID) ?: -1

        // Highlight the requesting mask with yellow border
        sendHighlightCommand(instanceId, true)

        setupListeners(instanceId)
    }

    private fun setupListeners() {
        binding.buttonCloseScreenMaskSettings.setOnClickListener {
            activity?.finish() // Close the hosting ScreenMaskActivity
        }

        // Lock button for individual mask
        binding.lockButton.setOnClickListener {
            val instanceId = arguments?.getInt(ARG_INSTANCE_ID) ?: return@setOnClickListener
            sendLockCommand(instanceId)
        }

        // Lock All button
        binding.lockAllButton.setOnClickListener {
            sendLockAllCommand()
        }

        // Billboard button
        binding.billboardButton.setOnClickListener {
            val instanceId = arguments?.getInt(ARG_INSTANCE_ID) ?: return@setOnClickListener
            sendBillboardCommand(instanceId)
        }

        // Add New Mask
        binding.buttonAddNewMask.setOnClickListener {
            val prefs = requireActivity().getSharedPreferences(ScreenMaskActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val activeCount = prefs.getInt(ScreenMaskActivity.KEY_ACTIVE_COUNT, 0)
            val maxMasks = 4 // MAX_MASKS defined in ScreenMaskService, ideally from a shared const

            if (activeCount < maxMasks) {
                val serviceIntent = Intent(requireContext(), ScreenMaskService::class.java).apply {
                    action = ACTION_ADD_NEW_MASK_INSTANCE
                }
                ContextCompat.startForegroundService(requireContext(), serviceIntent)
                // Optionally, you could close settings after adding, or let the user add more.
                // For now, let's keep it open.
            } else {
                Snackbar.make(binding.root, getString(R.string.max_masks_reached_snackbar), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun sendLockCommand(instanceId: Int) {
        val intent = Intent(requireContext(), ScreenMaskService::class.java).apply {
            action = ACTION_TOGGLE_LOCK
            putExtra(EXTRA_MASK_INSTANCE_ID, instanceId)
        }
        requireContext().startService(intent)
    }

    private fun sendLockAllCommand() {
        val intent = Intent(requireContext(), ScreenMaskService::class.java).apply {
            action = ACTION_TOGGLE_LOCK_ALL
        }
        requireContext().startService(intent)
    }

    private fun sendBillboardCommand(instanceId: Int) {
        // This triggers the image picker via the service
        val intent = Intent(requireContext(), ScreenMaskService::class.java).apply {
            action = ACTION_REQUEST_IMAGE_CHOOSER
            putExtra(EXTRA_MASK_INSTANCE_ID, instanceId)
        }
        requireContext().startService(intent)
    }

    override fun onDestroyView() {
        // Remove highlight when closing
        val instanceId = arguments?.getInt(ARG_INSTANCE_ID) ?: -1
        if (instanceId != -1) {
            // Send intent to service to remove highlight
            val intent = Intent(requireContext(), ScreenMaskService::class.java).apply {
                action = ACTION_REMOVE_HIGHLIGHT
                putExtra(EXTRA_MASK_INSTANCE_ID, instanceId)
            }
            requireContext().startService(intent)
        }
        super.onDestroyView()
    }
}