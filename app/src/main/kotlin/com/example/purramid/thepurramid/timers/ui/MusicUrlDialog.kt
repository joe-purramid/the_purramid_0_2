// MusicUrlDialog.kt
package com.example.purramid.thepurramid.timers.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.DialogMusicUrlBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicUrlDialog : DialogFragment() {
    
    private var _binding: DialogMusicUrlBinding? = null
    private val binding get() = _binding!!
    
    private var currentMusicUrl: String? = null
    private var recentUrls: List<String> = emptyList()
    private var onUrlSetListener: ((String?) -> Unit)? = null
    
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    
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
        _binding = DialogMusicUrlBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupUI()
        setupListeners()
        populateRecentUrls()
    }
    
    private fun setupUI() {
        // Set current URL if exists
        currentMusicUrl?.let {
            binding.editTextUrl.setText(it.removePrefix("https://"))
        }
        
        // Setup play button initial state
        updatePlayButtonState(false)
    }
    
    private fun setupListeners() {
        // Back button
        binding.buttonBack.setOnClickListener {
            dismiss()
        }
        
        // Close button (saves and closes)
        binding.buttonClose.setOnClickListener {
            val url = getFullUrl()
            onUrlSetListener?.invoke(url)
            dismiss()
        }
        
        // Play/Stop button
        binding.buttonPlay.setOnClickListener {
            if (isPlaying) {
                stopPlayback()
            } else {
                playUrl()
            }
        }
        
        // Text field listeners
        binding.editTextUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Stop playback if URL changes
                if (isPlaying) {
                    stopPlayback()
                }
            }
        })
        
        // Long press for paste
        binding.editTextUrl.setOnLongClickListener {
            showPasteOption()
            true
        }
    }
    
    private fun populateRecentUrls() {
        binding.recentUrlsContainer.removeAllViews()
        
        recentUrls.take(3).forEach { url ->
            val urlView = LayoutInflater.from(context).inflate(
                R.layout.item_recent_url, 
                binding.recentUrlsContainer, 
                false
            )
            
            val textView = urlView.findViewById<android.widget.TextView>(R.id.textViewUrl)
            textView.text = url
            
            urlView.setOnClickListener {
                binding.editTextUrl.setText(url.removePrefix("https://"))
                // Move to top of recent list is handled by ViewModel
            }
            
            binding.recentUrlsContainer.addView(urlView)
        }
        
        // Show/hide the recent URLs section
        binding.labelRecentUrls.visibility = if (recentUrls.isEmpty()) View.GONE else View.VISIBLE
        binding.recentUrlsContainer.visibility = if (recentUrls.isEmpty()) View.GONE else View.VISIBLE
    }
    
    private fun showPasteOption() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        
        if (clipData != null && clipData.itemCount > 0) {
            val pastedText = clipData.getItemAt(0).text?.toString()
            if (!pastedText.isNullOrEmpty()) {
                // Remove https:// prefix if present
                val cleanUrl = pastedText.removePrefix("https://").removePrefix("http://")
                binding.editTextUrl.setText(cleanUrl)
                binding.editTextUrl.setSelection(cleanUrl.length)
            }
        } else {
            Toast.makeText(context, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun getFullUrl(): String? {
        val text = binding.editTextUrl.text.toString().trim()
        return if (text.isNotEmpty()) {
            if (text.startsWith("http://") || text.startsWith("https://")) {
                text
            } else {
                "https://$text"
            }
        } else {
            null
        }
    }
    
    private fun playUrl() {
        val url = getFullUrl()
        if (url.isNullOrEmpty()) {
            showError(getString(R.string.enter_url_first))
            return
        }
        
        updatePlayButtonState(true, loading = true)
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(url)
                    prepareAsync()
                    
                    setOnPreparedListener {
                        start()
                        isPlaying = true
                        lifecycleScope.launch(Dispatchers.Main) {
                            updatePlayButtonState(true, loading = false)
                        }
                    }
                    
                    setOnCompletionListener {
                        lifecycleScope.launch(Dispatchers.Main) {
                            stopPlayback()
                        }
                    }
                    
                    setOnErrorListener { _, _, _ ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            showError(getString(R.string.invalid_music_url))
                            stopPlayback()
                        }
                        true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing URL: $url", e)
                withContext(Dispatchers.Main) {
                    showError(getString(R.string.invalid_music_url))
                    updatePlayButtonState(false)
                }
            }
        }
    }
    
    private fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        updatePlayButtonState(false)
    }
    
    private fun updatePlayButtonState(playing: Boolean, loading: Boolean = false) {
        when {
            loading -> {
                binding.buttonPlay.setImageResource(R.drawable.ic_play)
                binding.buttonPlay.alpha = 0.5f
                binding.buttonPlay.isEnabled = false
            }
            playing -> {
                binding.buttonPlay.setImageResource(R.drawable.ic_play)
                binding.buttonPlay.setColorFilter(
                    ContextCompat.getColor(requireContext(), R.color.timer_active_color),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
                binding.buttonPlay.alpha = 1.0f
                binding.buttonPlay.isEnabled = true
            }
            else -> {
                binding.buttonPlay.setImageResource(R.drawable.ic_play)
                binding.buttonPlay.colorFilter = null
                binding.buttonPlay.alpha = 1.0f
                binding.buttonPlay.isEnabled = true
            }
        }
    }
    
    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        stopPlayback()
        _binding = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
    
    companion object {
        private const val TAG = "MusicUrlDialog"
        
        fun newInstance(
            currentUrl: String?,
            recentUrls: List<String>,
            onUrlSet: (String?) -> Unit
        ): MusicUrlDialog {
            return MusicUrlDialog().apply {
                this.currentMusicUrl = currentUrl
                this.recentUrls = recentUrls
                this.onUrlSetListener = onUrlSet
            }
        }
    }
}