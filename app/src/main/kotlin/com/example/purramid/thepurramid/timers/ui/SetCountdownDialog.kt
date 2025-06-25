// SetCountdownDialog.kt
package com.example.purramid.thepurramid.timers.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.DialogSetCountdownBinding
import java.util.concurrent.TimeUnit

class SetCountdownDialog : DialogFragment() {
    
    private var _binding: DialogSetCountdownBinding? = null
    private val binding get() = _binding!!
    
    private var initialDurationMillis: Long = 0
    private var onDurationSetListener: ((Long) -> Unit)? = null
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSetCountdownBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Set initial duration
        val hours = TimeUnit.MILLISECONDS.toHours(initialDurationMillis).toInt()
        val minutes = (TimeUnit.MILLISECONDS.toMinutes(initialDurationMillis) % 60).toInt()
        val seconds = (TimeUnit.MILLISECONDS.toSeconds(initialDurationMillis) % 60).toInt()
        
        binding.durationPicker.setDuration(hours, minutes, seconds)
        
        // Set up quick add buttons
        binding.buttonAdd1Hour.setOnClickListener {
            addTime(1, 0, 0)
        }
        
        binding.buttonAdd5Minutes.setOnClickListener {
            addTime(0, 5, 0)
        }
        
        binding.buttonAdd30Seconds.setOnClickListener {
            addTime(0, 0, 30)
        }
        
        // Set up action buttons
        binding.buttonCancel.setOnClickListener {
            dismiss()
        }
        
        binding.buttonSet.setOnClickListener {
            onDurationSetListener?.invoke(binding.durationPicker.getDurationMillis())
            dismiss()
        }
        
        // Listen for duration changes
        binding.durationPicker.onDurationChangeListener = { h, m, s ->
            // Could update preview or validation here
        }
    }
    
    private fun addTime(hours: Int, minutes: Int, seconds: Int) {
        val currentMillis = binding.durationPicker.getDurationMillis()
        val addMillis = hours * 3600000L + minutes * 60000L + seconds * 1000L
        val newMillis = (currentMillis + addMillis).coerceAtMost(99 * 3600000L + 59 * 60000L + 59 * 1000L)
        
        val newHours = TimeUnit.MILLISECONDS.toHours(newMillis).toInt()
        val newMinutes = (TimeUnit.MILLISECONDS.toMinutes(newMillis) % 60).toInt()
        val newSeconds = (TimeUnit.MILLISECONDS.toSeconds(newMillis) % 60).toInt()
        
        binding.durationPicker.setDuration(newHours, newMinutes, newSeconds)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    companion object {
        fun newInstance(
            initialDurationMillis: Long,
            onDurationSet: (Long) -> Unit
        ): SetCountdownDialog {
            return SetCountdownDialog().apply {
                this.initialDurationMillis = initialDurationMillis
                this.onDurationSetListener = onDurationSet
            }
        }
    }
}