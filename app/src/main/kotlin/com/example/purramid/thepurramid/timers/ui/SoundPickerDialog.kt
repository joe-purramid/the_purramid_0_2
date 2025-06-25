// SoundPickerDialog.kt
package com.example.purramid.thepurramid.timers.ui

import android.app.Dialog
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.DialogSoundPickerBinding
import com.example.purramid.thepurramid.databinding.ItemSoundBinding

class SoundPickerDialog : DialogFragment() {
    
    private var _binding: DialogSoundPickerBinding? = null
    private val binding get() = _binding!!
    
    private var selectedSoundUri: String? = null
    private var onSoundSelectedListener: ((String?) -> Unit)? = null
    
    private lateinit var soundAdapter: SoundAdapter
    private val soundList = mutableListOf<SoundItem>()
    
    data class SoundItem(
        val title: String,
        val uri: Uri?,
        val isNone: Boolean = false,
        val isMusic: Boolean = false
    )
    
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
        _binding = DialogSoundPickerBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        loadSounds()
        setupListeners()
    }
    
    private fun setupRecyclerView() {
        soundAdapter = SoundAdapter { soundItem ->
            if (soundItem.isMusic) {
                // Show music URL dialog
                showMusicUrlDialog()
            } else {
                // Select this sound
                selectedSoundUri = soundItem.uri?.toString()
                onSoundSelectedListener?.invoke(selectedSoundUri)
                dismiss()
            }
        }
        
        binding.recyclerViewSounds.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = soundAdapter
        }
    }
    
    private fun loadSounds() {
        soundList.clear()
        
        // Add "None" option
        soundList.add(SoundItem(
            title = getString(R.string.sound_none),
            uri = null,
            isNone = true
        ))
        
        // Add system notification sounds
        val ringtoneManager = RingtoneManager(requireContext())
        ringtoneManager.setType(RingtoneManager.TYPE_NOTIFICATION)
        
        val cursor = ringtoneManager.cursor
        while (cursor.moveToNext()) {
            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
            val uri = ringtoneManager.getRingtoneUri(cursor.position)
            soundList.add(SoundItem(title, uri))
        }
        
        // Add system alarm sounds
        ringtoneManager.setType(RingtoneManager.TYPE_ALARM)
        val alarmCursor = ringtoneManager.cursor
        while (alarmCursor.moveToNext()) {
            val title = alarmCursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) + " (Alarm)"
            val uri = ringtoneManager.getRingtoneUri(alarmCursor.position)
            soundList.add(SoundItem(title, uri))
        }
        
        // Add "Music" option at the end
        soundList.add(SoundItem(
            title = getString(R.string.sound_music),
            uri = null,
            isMusic = true
        ))
        
        soundAdapter.submitList(soundList)
    }
    
    private fun setupListeners() {
        binding.buttonCancel.setOnClickListener {
            dismiss()
        }
    }
    
    private fun showMusicUrlDialog() {
        dismiss()
        // This will be handled by the parent fragment/activity
        onSoundSelectedListener?.invoke("MUSIC_URL_OPTION")
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    companion object {
        fun newInstance(
            currentSoundUri: String?,
            onSoundSelected: (String?) -> Unit
        ): SoundPickerDialog {
            return SoundPickerDialog().apply {
                this.selectedSoundUri = currentSoundUri
                this.onSoundSelectedListener = onSoundSelected
            }
        }
    }
    
    // Sound Adapter
    inner class SoundAdapter(
        private val onItemClick: (SoundItem) -> Unit
    ) : RecyclerView.Adapter<SoundAdapter.SoundViewHolder>() {
        
        private var items = listOf<SoundItem>()
        
        fun submitList(list: List<SoundItem>) {
            items = list
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SoundViewHolder {
            val binding = ItemSoundBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return SoundViewHolder(binding)
        }
        
        override fun onBindViewHolder(holder: SoundViewHolder, position: Int) {
            holder.bind(items[position])
        }
        
        override fun getItemCount() = items.size
        
        inner class SoundViewHolder(
            private val binding: ItemSoundBinding
        ) : RecyclerView.ViewHolder(binding.root) {
            
            init {
                binding.root.setOnClickListener {
                    onItemClick(items[adapterPosition])
                }
            }
            
            fun bind(item: SoundItem) {
                binding.textViewSoundName.text = item.title
                
                // Show icon based on type
                when {
                    item.isNone -> binding.imageViewIcon.setImageResource(R.drawable.ic_sound_off)
                    item.isMusic -> binding.imageViewIcon.setImageResource(R.drawable.ic_music_note)
                    else -> binding.imageViewIcon.setImageResource(R.drawable.ic_notification_sound)
                }
                
                // Highlight if selected
                val isSelected = when {
                    item.isNone && selectedSoundUri == null -> true
                    item.uri != null && item.uri.toString() == selectedSoundUri -> true
                    else -> false
                }
                
                binding.root.isSelected = isSelected
                binding.imageViewCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            }
        }
    }
}