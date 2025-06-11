// ListEditorActivity.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.SpinListEntity
import com.example.purramid.thepurramid.databinding.ActivityListEditorBinding
import com.example.purramid.thepurramid.randomizers.viewmodel.ListEditorViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.async // Import async

class ListEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListEditorBinding
    private val viewModel: ListEditorViewModel by viewModels()
    private lateinit var listAdapter: ListEditorAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        listAdapter = ListEditorAdapter(
            onDeleteClick = { listEntity -> showDeleteConfirmation(listEntity) },
            onListClick = { listEntity -> navigateToListCreator(listEntity.id) }
        )
        binding.recyclerViewLists.apply {
            adapter = listAdapter
            layoutManager = LinearLayoutManager(this@ListEditorActivity)
        }
    }

    private fun setupListeners() {
        binding.buttonCloseListEditor.setOnClickListener { finish() }
        // binding.buttonBackListEditor?.setOnClickListener { finish() } // If back button is added
        binding.buttonAddNewList.setOnClickListener { navigateToListCreator(null) }
    }

    private fun observeViewModel() {
        viewModel.allSpinLists.observe(this) { lists ->
            // We need counts for each list to display "Title (Count)"
            // Launch coroutine to get counts asynchronously
            lifecycleScope.launch {
                val listDisplayItems = lists?.map { listEntity ->
                    // Use async to fetch count for each list potentially in parallel
                    val countDeferred = async { viewModel.getItemCountForList(listEntity.id) }
                    ListDisplayItem(entity = listEntity, count = countDeferred.await())
                } ?: emptyList()

                listAdapter.submitList(listDisplayItems)
            }
        }
    }

    private fun showDeleteConfirmation(listEntity: SpinListEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_list_title)) // TODO: Add string
            .setMessage(getString(R.string.delete_list_confirmation, listEntity.title)) // TODO: Add string (e.g., "Are you sure you want to delete '%1$s'?")
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> // TODO: Add string
                dialog.dismiss()
            }
            .setPositiveButton(getString(R.string.delete)) { dialog, _ -> // TODO: Add string
                viewModel.deleteList(listEntity)
                dialog.dismiss()
            }
            .show()
    }

    private fun navigateToListCreator(listIdToEdit: UUID?) {
        // TODO: Implement navigation to ListCreatorActivity
        // val intent = Intent(this, ListCreatorActivity::class.java)
        // listIdToEdit?.let { intent.putExtra(ListCreatorActivity.EXTRA_LIST_ID, it.toString()) }
        // startActivity(intent)
         android.widget.Toast.makeText(this, "Navigate to Creator (TODO): Edit ID = $listIdToEdit", android.widget.Toast.LENGTH_SHORT).show()
    }
}