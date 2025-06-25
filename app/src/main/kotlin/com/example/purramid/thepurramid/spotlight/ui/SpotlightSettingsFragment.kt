// SpotlightSettingsFragment.kt
package com.example.purramid.thepurramid.spotlight.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.FragmentSpotlightSettingsBinding
import com.example.purramid.thepurramid.spotlight.ACTION_ADD_NEW_SPOTLIGHT_INSTANCE
import com.example.purramid.thepurramid.spotlight.SpotlightService
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SpotlightSettingsFragment : Fragment() {

    private var _binding: FragmentSpotlightSettingsBinding? = null
    private val binding get() = _binding!!

    // No direct ViewModel needed for this simple version if it only adds.
    // If it were to list/manage existing spotlights, it would need one.

    companion object {
        const val TAG = "SpotlightSettingsFragment"
        fun newInstance(): SpotlightSettingsFragment {
            return SpotlightSettingsFragment()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSpotlightSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
    }

    private fun setupListeners() {
        binding.buttonCloseSpotlightSettings.setOnClickListener {
            activity?.finish() // Or findNavController().popBackStack()
        }

        binding.buttonAddNewSpotlight.setOnClickListener {
            val prefs = requireActivity().getSharedPreferences(SpotlightService.PREFS_NAME_FOR_ACTIVITY, Context.MODE_PRIVATE)
            val activeCount = prefs.getInt(SpotlightService.KEY_ACTIVE_COUNT_FOR_ACTIVITY, 0)

            if (activeCount < SpotlightService.MAX_SPOTLIGHTS) {
                Log.d(TAG, "Add new spotlight requested from settings.")
                val serviceIntent = Intent(requireContext(), SpotlightService::class.java).apply {
                    action = ACTION_ADD_NEW_SPOTLIGHT_INSTANCE
                }
                ContextCompat.startForegroundService(requireContext(), serviceIntent)
            } else {
                Snackbar.make(binding.root, getString(R.string.max_spotlights_reached_snackbar), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}