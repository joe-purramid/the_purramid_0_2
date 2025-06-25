// ScreenMaskActivity.kt
package com.example.purramid.thepurramid.screen_mask

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.transition.Explode
import android.util.Log
import android.view.Gravity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.ActivityScreenMaskBinding
import com.example.purramid.thepurramid.screen_mask.ui.ScreenMaskSettingsFragment
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class ScreenMaskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScreenMaskBinding
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>

    companion object {
        private const val TAG = "ScreenMaskActivity"
        const val ACTION_LAUNCH_IMAGE_CHOOSER_FROM_SERVICE = "com.example.purramid.screen_mask.ACTION_LAUNCH_IMAGE_CHOOSER_FROM_SERVICE"
        // Using the constants defined in ScreenMaskService for SharedPreferences
        const val PREFS_NAME = ScreenMaskService.PREFS_NAME_FOR_ACTIVITY
        const val KEY_ACTIVE_COUNT = ScreenMaskService.KEY_ACTIVE_COUNT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenMaskBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate - Intent Action: ${intent.action}")

        // Explosion transition (appears in center)
        window.enterTransition = Explode().apply {
            duration = 300
        }
        window.exitTransition = Explode().apply {
            duration = 300
        }

        // Initialize image picker launcher (keep existing functionality)
        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                Log.d(TAG, "Image selected: $uri")

                // Check image size before forwarding
                val fileSize = getFileSize(uri)
                if (fileSize > 3 * 1024 * 1024) { // 3MB in bytes
                    showImageSizeDialog(uri)
                } else {
                    sendImageUriToService(uri)
                    finish()
                }
            } else {
                Log.d(TAG, "No image selected from picker.")
                sendImageUriToService(null)
                finish()
            }
        }

        // Check the action that started this activity
        when (intent.action) {
            ACTION_LAUNCH_IMAGE_CHOOSER_FROM_SERVICE -> {
                Log.d(TAG, "Launched by service to pick image.")
                openImageChooser()
            }
            else -> {
                // Default: Show settings
                val requestingInstanceId = intent.getIntExtra(EXTRA_MASK_INSTANCE_ID, -1)
                showSettingsFragment(requestingInstanceId)
            }
        }
    }

    private fun openImageChooser() {
        try {
            imagePickerLauncher.launch("image/*")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open image picker", e)
            Snackbar.make(binding.root, getString(R.string.cannot_open_image_picker), Snackbar.LENGTH_LONG).show()
            finish()
        }
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize
            } ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size", e)
            0L
        }
    }

    private fun showImageSizeDialog(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.image_too_large_title))
            .setMessage(getString(R.string.image_too_large_message))
            .setPositiveButton(getString(R.string.optimize)) { _, _ ->
                compressAndSendImage(uri)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                // Return to picker
                openImageChooser()
            }
            .setCancelable(false)
            .show()
    }

    private fun compressAndSendImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val compressedUri = compressImage(uri)
                withContext(Dispatchers.Main) {
                    sendImageUriToService(compressedUri)
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error compressing image", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScreenMaskActivity, getString(R.string.compression_failed), Toast.LENGTH_LONG).show()
                    openImageChooser()
                }
            }
        }
    }

    private suspend fun compressImage(uri: Uri): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }

                // Calculate compression quality to get close to 3MB
                var quality = 90
                var outputStream: ByteArrayOutputStream
                var compressed: ByteArray

                do {
                    outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                    compressed = outputStream.toByteArray()
                    quality -= 10
                } while (compressed.size > 3 * 1024 * 1024 && quality > 10)

                // Save compressed image to cache
                val fileName = "compressed_${System.currentTimeMillis()}.jpg"
                val file = File(cacheDir, fileName)
                file.writeBytes(compressed)

                // Return URI of compressed file
                FileProvider.getUriForFile(this@ScreenMaskActivity, "${packageName}.fileprovider", file)

            } catch (e: Exception) {
                Log.e(TAG, "Compression error", e)
                null
            }
        }
    }

    private fun sendImageUriToService(uri: Uri?) {
        val serviceIntent = Intent(this, ScreenMaskService::class.java).apply {
            action = ACTION_BILLBOARD_IMAGE_SELECTED
            putExtra(EXTRA_IMAGE_URI, uri?.toString()) // Send URI as String
            // The service knows which instance requested it via imageChooserTargetInstanceId
        }
        // Use startService for simple data passing intents that don't require foreground lifecycle
        startService(serviceIntent)
    }

    private fun showSettingsFragment(instanceId: Int) {
        val fragment = ScreenMaskSettingsFragment.newInstance(instanceId)

        supportFragmentManager.beginTransaction()
            .replace(R.id.screen_mask_fragment_container, fragment)
            .commit()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent with the new one
        handleIntent(intent)
    }

    // Centralized intent handling logic
    private fun handleIntent(intent: Intent) {
        Log.d(TAG, "handleIntent - Action: ${intent.action}")
        if (intent.action == ACTION_LAUNCH_IMAGE_CHOOSER_FROM_SERVICE) {
            Log.d(TAG, "Launched by service to pick image.")
            openImageChooser()
            // Activity will finish after imagePickerLauncher returns if openImageChooser calls finish()
        } else {
            // Default launch path or if reordered to front without specific known action
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val activeCount = prefs.getInt(KEY_ACTIVE_COUNT, 0)

            if (activeCount > 0) {
                Log.d(TAG, "Screen Masks active ($activeCount), launching settings fragment.")
                showSettingsFragment()
                // Activity remains open to host the fragment
            } else {
                Log.d(TAG, "No active Screen Masks, requesting service to add a new one.")
                val serviceIntent = Intent(this, ScreenMaskService::class.java).apply {
                    action = ACTION_ADD_NEW_MASK_INSTANCE
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                finish() // Finish after telling service to add the first instance
            }
        }
    }
}