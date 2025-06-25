// SlotsMainFragment.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.net.Uri // Import Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView // Import ImageView
import android.widget.LinearLayout // Import LinearLayout
import android.widget.TextView
import android.widget.Toast // For error messages
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide // Import Glide
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.SpinItemEntity
import com.example.purramid.thepurramid.data.db.SpinListEntity
import com.example.purramid.thepurramid.databinding.FragmentSlotsMainBinding // Use Fragment binding
import com.example.purramid.thepurramid.randomizers.SlotsColumnState
import com.example.purramid.thepurramid.randomizers.SpinItemType
import com.example.purramid.thepurramid.randomizers.viewmodel.SlotsResult
import com.example.purramid.thepurramid.randomizers.viewmodel.SlotsViewModel
import com.example.purramid.thepurramid.util.dpToPx
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID

@AndroidEntryPoint
class SlotsMainFragment : Fragment() {

    private var _binding: FragmentSlotsMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SlotsViewModel by viewModels()

    private lateinit var columnViews: List<SlotColumnView>
    private lateinit var listSelectionAdapter: ArrayAdapter<String>
    private var availableLists: List<SpinListEntity> = emptyList()

    // LinearLayout within the announcement overlay to add result views to
    private var announcementResultsContainer: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSlotsMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Find the container within the overlay (assuming it has an ID or is the first child)
        announcementResultsContainer = binding.slotsAnnouncementOverlay.findViewById(R.id.slotsAnnouncementResultsLayout)
        // If you didn't add an ID, you might need to get the child LinearLayout differently:
        // if (binding.slotsAnnouncementOverlay.childCount > 0 && binding.slotsAnnouncementOverlay.getChildAt(0) is LinearLayout) {
        //     announcementResultsContainer = binding.slotsAnnouncementOverlay.getChildAt(0) as LinearLayout
        // }

        initializeColumnViews()
        setupUIListeners()
        observeViewModel()

        listSelectionAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        announcementResultsContainer = null // Clear reference
        _binding = null
    }

    private fun initializeColumnViews() {
        columnViews = listOfNotNull(
            binding.slotColumnView1,
            binding.slotColumnView2,
            binding.slotColumnView3,
            binding.slotColumnView4,
            binding.slotColumnView5
        )
    }

    private fun setupUIListeners() {
        binding.slotsCloseButton.setOnClickListener {
            viewModel.handleManualClose() // Call ViewModel to clean up DB
            activity?.finish() // Finish the hosting RandomizersHostActivity
        }
        binding.slotsSettingsButton.setOnClickListener {
            viewModel.instanceId?.let { id ->
                try {
                     // *** Assumes navigation action exists in randomizers_nav_graph.xml ***
                     val action = SlotsMainFragmentDirections.actionSlotsMainFragmentToSettingsFragment(id.toString())
                     findNavController().navigate(action)
                } catch (e: Exception) {
                     Log.e("SlotsMainFragment", "Navigation to Settings failed. Ensure NavGraph action exists.", e)
                     Toast.makeText(context, "Cannot open settings.", Toast.LENGTH_SHORT).show() // Inform user
                }
            } ?: run {
                 Toast.makeText(context, "Cannot open settings: Invalid ID", Toast.LENGTH_SHORT).show()
            }
        }
        binding.slotsSpinButton.setOnClickListener {
            binding.slotsAnnouncementOverlay.isVisible = false
            viewModel.spinAllUnlocked()
        }

        columnViews.forEachIndexed { index, columnView ->
            columnView.setOnTitleClickListener { showListSelectionDialog(index) }
            columnView.setOnLockClickListener { viewModel.toggleLockForColumn(index) }
        }
    }

    private fun observeViewModel() {
        val lifecycleOwner = viewLifecycleOwner

        viewModel.settings.observe(lifecycleOwner) { settings ->
            settings?.let {
                 // *** Assumes settings.numSlotsColumns exists ***
                 val numColumns = settings.numSlotsColumns
                 updateColumnCount(numColumns)
            }
        }

        viewModel.columnStates.observe(lifecycleOwner) { states ->
            updateColumnsUI(states)
        }

        viewModel.allSpinLists.observe(lifecycleOwner) { lists ->
            availableLists = lists ?: emptyList()
            listSelectionAdapter.clear()
            listSelectionAdapter.addAll(availableLists.map { it.title })
        }

        viewModel.isSpinning.observe(lifecycleOwner) { spinningMap ->
             columnViews.forEachIndexed { index, columnView ->
                 if (spinningMap[index] == true) {
                     columnView.startSpinAnimation()
                 }
             }
        }

        viewModel.spinResult.observe(lifecycleOwner) { slotsResult ->
            slotsResult?.let {
                 handleSpinResult(it)
                 viewModel.clearSpinResult()
            }
        }

        viewModel.errorEvent.observe(lifecycleOwner) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show() // Show error via Toast
                viewModel.clearErrorEvent()
            }
        }
    }

    private fun updateColumnCount(count: Int) {
        columnViews.forEachIndexed { index, columnView ->
            columnView.isVisible = index < count
        }
    }

    private fun updateColumnsUI(states: List<SlotsColumnState>) {
        states.forEach { state ->
            if (state.columnIndex < columnViews.size) {
                val columnView = columnViews[state.columnIndex]
                columnView.setLockedState(state.isLocked)
                val listTitle = availableLists.firstOrNull { it.id == state.selectedListId }?.title
                columnView.setTitle(listTitle)

                // Get data from ViewModel cache/fetch mechanism and set it on the view
                val currentListItems = viewModel.getItemsForColumn(state.columnIndex) ?: emptyList() // Provide empty list if null
                columnView.setData(currentListItems, state.currentItemId)
            }
        }
    }

    private fun handleSpinResult(result: SlotsResult) {
        // 1. Stop animations and display final items
        result.results.forEach { (state, finalItem) ->
             if (state.columnIndex < columnViews.size) {
                 val columnView = columnViews[state.columnIndex]
                 // Call stopSpinAnimation with the final item ID. The view will handle display.
                 columnView.stopSpinAnimation(finalItem?.id)
             }
        }

        // 2. Show announcement if enabled
        // *** Assumes settings.isAnnounceEnabled exists ***
        val announceEnabled = viewModel.settings.value?.isAnnounceEnabled ?: false
        if (announceEnabled) {
             showAnnouncement(result)
        }
    }

    private fun showAnnouncement(result: SlotsResult) {
         announcementResultsContainer?.let { container ->
             container.removeAllViews() // Clear previous results

             result.results.forEach { (state, item) ->
                 // Create a view (TextView or ImageView) for each result item
                 val viewToAdd: View = createViewForResultItem(item)
                 // Add some layout params if needed (e.g., margins)
                 val params = LinearLayout.LayoutParams(
                     LinearLayout.LayoutParams.WRAP_CONTENT,
                     LinearLayout.LayoutParams.WRAP_CONTENT
                 ).apply {
                     marginEnd = resources.getDimensionPixelSize(R.dimen.small_padding) // Example padding
                 }
                 viewToAdd.layoutParams = params
                 container.addView(viewToAdd)
             }
             binding.slotsAnnouncementOverlay.isVisible = true
             binding.slotsAnnouncementOverlay.setOnClickListener {
                 it.isVisible = false // Dismiss on tap
             }
         } ?: run {
            Log.e("SlotsMainFragment", "Announcement results container not found in overlay layout!")
         }
    }

    /** Creates a TextView or ImageView for a result item */
    private fun createViewForResultItem(item: SpinItemEntity?): View {
        val context = requireContext()
        val defaultSize = requireContext().dpToPx(100) // Example size, adjust as needed
        val defaultPadding = requireContext().dpToPx(8) // Example padding

        if (item == null) {
            // Handle null item (e.g., empty list)
            return TextView(context).apply {
                text = "-" // Placeholder for empty
                textSize = 24f // Example text size
                setPadding(defaultPadding, defaultPadding, defaultPadding, defaultPadding)
            }
        }

        return when (item.itemType) {
            SpinItemType.IMAGE -> {
                // --- START IMAGE IMPLEMENTATION ---
                ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(defaultSize, defaultSize) // Set a reasonable size
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(defaultPadding, defaultPadding, defaultPadding, defaultPadding)
                    contentDescription = getString(R.string.item_image_content_description) // Accessibility
                    try {
                        Glide.with(this@SlotsMainFragment) // Use fragment context
                            .load(Uri.parse(item.content))
                            // Add placeholders/error drawables if desired
                            // .placeholder(R.drawable.loading_spinner)
                            // .error(R.drawable.error_placeholder)
                            .into(this)
                    } catch (e: Exception) {
                        Log.e("SlotsMainFragment", "Failed to load image in announcement: ${item.content}", e)
                        // Optionally set an error drawable if Glide fails
                        setImageResource(R.drawable.ic_broken_image) // TODO: Add a suitable error drawable
                    }
                }
            }
            SpinItemType.EMOJI -> {
                TextView(context).apply {
                    textSize = 32f // Larger size for emoji
                    setPadding(defaultPadding, defaultPadding, defaultPadding, defaultPadding)
                }
            }
            SpinItemType.TEXT -> {
                TextView(context).apply {
                    textSize = 24f // Example text size
                    setPadding(defaultPadding, defaultPadding, defaultPadding, defaultPadding)
                }
            }
        }
    }

    private fun showListSelectionDialog(columnIndex: Int) {
        if (availableLists.isEmpty()) {
            Toast.makeText(context, "No lists available to select.", Toast.LENGTH_SHORT).show() // TODO: String resource
            return
        }
        val currentListId = viewModel.columnStates.value?.getOrNull(columnIndex)?.selectedListId

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.select_list_for_column, columnIndex + 1)) // TODO: String resource
            .setSingleChoiceItems(
                availableLists.map { it.title }.toTypedArray(), // Display titles
                availableLists.indexOfFirst { it.id == currentListId } // Pre-select current item
            ) { dialog, which ->
                val selectedList = availableLists[which]
                viewModel.selectListForColumn(columnIndex, selectedList.id)
                dialog.dismiss() // Dismiss on selection
            }
            .setNegativeButton(R.string.cancel, null)
             .setNeutralButton(R.string.list_selection_clear) { dialog, _ -> // TODO: String resource
                 viewModel.selectListForColumn(columnIndex, null)
                 dialog.dismiss()
             }
            .show()
    }
}

// TODO: Add required String resources:
// R.string.settings_navigation_failed (e.g., "Navigation to Settings failed...")

// TODO: Add Navigation action from SlotsMainFragment to Settings Fragment/Activity in nav graph
// Example action ID: action_slotsMainFragment_to_settingsFragment

// TODO: Add R.id.slotsAnnouncementResultsLayout to the LinearLayout inside slotsAnnouncementOverlay in fragment_slots_main.xml