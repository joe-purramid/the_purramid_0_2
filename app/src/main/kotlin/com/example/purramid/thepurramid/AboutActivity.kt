package com.example.purramid.thepurramid

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.purramid.thepurramid.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAboutBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupCloseButton()
        setInitialWindowSize()
    }
    
    private fun setupCloseButton() {
        binding.closeButton.setOnClickListener {
            // Animate button state change
            binding.closeButton.setColorFilter(
                ContextCompat.getColor(this, android.R.color.darker_gray)
            )
            
            // Close the activity
            finish()
        }
    }
    
    private fun setInitialWindowSize() {
        // Set window size to 386px width by 500px height as per specifications
        window.setLayout(386, 500)
    }
}