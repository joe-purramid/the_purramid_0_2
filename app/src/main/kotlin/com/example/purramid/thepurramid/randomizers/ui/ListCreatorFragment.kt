// ListCreatorFragment.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.graphics.Color // Import Color for default
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast // For TODO placeholders
import androidx.activity.OnBackPressedCallback // Import for handling back press
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.SpinItemEntity
import com.example.purramid.thepurramid.databinding.FragmentListCreatorBinding // Use Fragment binding
import com.example.purramid.thepurramid.randomizers.viewmodel.ListCreatorViewModel
import com.github.dhaval2404.colorpicker.ColorPickerDialog
import com.github.dhaval2404.colorpicker.model.ColorShape
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vanniktech.emoji.EmojiPopup
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID // Import UUID

@AndroidEntryPoint
class ListCreatorFragment : Fragment(), ItemEditorAdapter.ItemEditorListener {

    private var _binding: FragmentListCreatorBinding? = null
    private val binding get() = _binding!!

    // Ensure correct ViewModel type is used
    private val viewModel: ListCreatorViewModel by viewModels()
    private lateinit var itemAdapter: ItemEditorAdapter

    private var editingColorForItem: SpinItemEntity? = null
    private var editingImageForItem: SpinItemEntity? = null
    private var editingEmojiForItem: SpinItemEntity? = null
    private var emojiPopup: EmojiPopup? = null

    // --- Activity Result Launcher for Image Picker ---
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) { // Use onCreate for launcher registration
        super.onCreate(savedInstanceState)

        // Register the activity result launcher
        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            // Handle the returned Uri
            uri?.let { selectedUri ->
                editingImageForItem?.let { item ->
                    // Persistable URI Permissions (Optional but Recommended for long-term access)
                    // Consider taking persistable URI permission if needed across device restarts
                    // val contentResolver = requireActivity().contentResolver
                    // val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    // contentResolver.takePersistableUriPermission(selectedUri, takeFlags)

                    viewModel.updateItemImage(item.id, selectedUri)
                }
            }
            // Clear the tracking variable whether successful or not
            editingImageForItem = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListCreatorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupListeners()
        observeViewModel()
        setupBackButtonInterceptor()
    }

    override fun onDestroyView() {
        emojiPopup?.dismiss()
        emojiPopup = null
        super.onDestroyView()
        _binding = null
        editingColorForItem = null
        editingImageForItem = null
        editingEmojiForItem = null
    }

    private fun setupRecyclerView() {
        itemAdapter = ItemEditorAdapter(this) // Pass 'this' as the listener
        binding.recyclerViewItems.apply {
            adapter = itemAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupListeners() {
        // Back/Close buttons trigger the exit sequence
        binding.buttonCloseCreator.setOnClickListener {
            handleExitAttempt()
        }
        binding.buttonBackCreator.setOnClickListener {
            handleExitAttempt()
        }

        // Add new item button
        binding.buttonAddItem.setOnClickListener {
            // ViewModel's addNewItem now handles saving the list title first if needed
            viewModel.addNewItem()
            // Scroll to bottom after item is potentially added
            binding.recyclerViewItems.postDelayed({
                if (_binding != null) { // Check binding still valid
                    binding.recyclerViewItems.smoothScrollToPosition(itemAdapter.itemCount)
                }
            }, 100)
        }

        // Update title in ViewModel immediately while typing
        binding.editTextListTitle.doAfterTextChanged { editable ->
            viewModel.updateListTitle(editable?.toString() ?: "")
        }
        // Save title *entity* when focus leaves the EditText (if title is not blank)
        binding.editTextListTitle.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                viewModel.saveListTitleOnly() // Persist title entity on focus loss
            }
        }
    }

    // Intercept back button press
    private fun setupBackButtonInterceptor() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleExitAttempt() // Use the same exit logic
            }
        })
    }

    /**
     * Handles the logic when the user attempts to exit the fragment
     * (via back button or close/back UI buttons).
     */
    private fun handleExitAttempt() {
        // Ensure latest title is saved to VM state first (doesn't write to DB yet unless focus lost)
        _binding?.let { viewModel.updateListTitle(it.editTextListTitle.text.toString()) }

        when {
            // Condition: New list, blank title, zero items -> Exit silently
            viewModel.isNewBlankListWithNoItems() -> {
                findNavController().popBackStack()
            }

            // Condition: New list, has title, but < 2 items -> Show warning
            viewModel.isNewListWithTitleButNotEnoughItems() -> {
                showMinItemsWarningDialog()
            }

            // Condition: Existing list OR New list with title and >= 2 items -> Save and exit
            else -> {
                // Check validity before final save/exit
                if (viewModel.isListStateValidForSaving()) {
                    viewModel.saveListTitleOnly() // Ensure final title is saved to DB
                    // Items are saved incrementally, so no extra save needed here
                    findNavController().popBackStack()
                } else {
                    // This case should ideally be caught by the specific checks above,
                    // but as a fallback, if state is invalid (e.g. blank title), delete potential DB entry and exit.
                    viewModel.deleteCurrentListAndItems()
                    findNavController().popBackStack()
                }
            }
        }
    }

    /** Shows the dialog warning the user that a new list needs at least 2 items. */
    private fun showMinItemsWarningDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.list_creator_cannot_save_title)) // Use new string resource
            .setMessage(getString(R.string.list_creator_min_items_message)) // Use new string resource
            .setNegativeButton(getString(R.string.list_creator_button_cancel_creation)) { dialog, _ ->
                // User chose to cancel: Delete potentially saved list and exit
                viewModel.deleteCurrentListAndItems()
                findNavController().popBackStack()
                dialog.dismiss()
            }
            .setPositiveButton(getString(R.string.list_creator_button_continue_editing)) { dialog, _ ->
                // User chose to continue: Just dismiss the dialog
                dialog.dismiss()
            }
            .setCancelable(false) // Don't allow dismissing by tapping outside
            .show()
    }


    private fun observeViewModel() {
        val lifecycleOwner = viewLifecycleOwner

        viewModel.listTitle.observe(lifecycleOwner) { title ->
            if (!binding.editTextListTitle.hasFocus() && binding.editTextListTitle.text.toString() != title) {
                binding.editTextListTitle.setText(title)
            }
        }

        viewModel.items.observe(lifecycleOwner) { items ->
            itemAdapter.submitList(items) {
                // Optional: If list updates while RecyclerView is computing layout, scroll might be needed
                // if (items.isNotEmpty() && !binding.recyclerViewItems.isComputingLayout) {
                //    binding.recyclerViewItems.smoothScrollToPosition(items.size - 1)
                // }
            }
        }

        viewModel.canAddItem.observe(lifecycleOwner) { canAdd ->
            binding.buttonAddItem.isEnabled = canAdd
        }
    }

    // --- ItemEditorListener Implementation ---

    override fun onDeleteItemClicked(item: SpinItemEntity) {
        MaterialAlertDialogBuilder(requireContext()) // Use Material Dialog
            .setTitle(R.string.delete_item_title) // TODO: Add string resource
            .setMessage(getString(R.string.delete_item_confirmation, item.content.take(20))) // TODO: Add string resource
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteItemFromList(item)
            }
            .show()
    }

    override fun onItemTextChanged(itemId: UUID, newText: String) {
        viewModel.updateItemText(itemId, newText)
    }

    override fun onColorClicked(item: SpinItemEntity, view: View) {
        editingColorForItem = item
        ColorPickerDialog
            .Builder(requireContext())
            .setTitle("Pick Item Color") // TODO: Add String resource
            .setColorShape(ColorShape.SQAURE)
            .setDefaultColor(item.backgroundColor ?: Color.LTGRAY)
            .setColorListener { color, colorHex ->
                editingColorForItem?.let { currentItem ->
                    viewModel.updateItemColor(currentItem.id, color)
                }
                editingColorForItem = null
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                editingColorForItem = null
                dialog.dismiss()
            }
            .show()
    }

    override fun onImageClicked(item: SpinItemEntity) {
        // Store the item being edited
        editingImageForItem = item
        // Launch the image picker
        try {
            imagePickerLauncher.launch("image/*") // Standard MIME type for images
        } catch (e: Exception) {
            // Handle potential exceptions if no app can handle the intent
            Toast.makeText(context, "Cannot open image picker", Toast.LENGTH_SHORT).show() // TODO: String resource
            editingImageForItem = null // Clear if launch fails
        }
    }

    override fun     override fun onEmojiClicked(item: SpinItemEntity, anchorView: View) {
        // Dismiss any existing popup first
        emojiPopup?.dismiss()
        editingEmojiForItem = item // Store the item we're adding emoji to

        // Example using vanniktech EmojiPopup
        emojiPopup = EmojiPopup.Builder.fromRootView(binding.root) // Requires root view
            .setOnEmojiClickListener { emoji ->
                // Emoji selected from popup
                editingEmojiForItem?.let { currentItem ->
                    viewModel.addEmojiToItem(currentItem.id, emoji.unicode)
                }
                emojiPopup?.dismiss() // Dismiss after selection
            }
            .setOnEmojiPopupDismissListener { editingEmojiForItem = null } // Clear item when dismissed
            .build(binding.editTextListTitle) // Needs an EditText to init, could be dummy hidden one if needed

        // Toggle the popup anchored to the button clicked
        emojiPopup?.toggle(anchorView)
    }
}