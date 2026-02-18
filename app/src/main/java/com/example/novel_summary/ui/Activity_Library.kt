package com.example.novel_summary.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.novel_summary.R
import com.example.novel_summary.data.model.Novel
import com.example.novel_summary.data.model.NovelWithStats
import com.example.novel_summary.databinding.ActivityLibraryBinding
import com.example.novel_summary.ui.adapter.LibraryAdapter
import com.example.novel_summary.ui.viewmodel.LibraryViewModel
import com.example.novel_summary.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class Activity_Library : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private val viewModel: LibraryViewModel by viewModels()

    private lateinit var adapter: LibraryAdapter
    private var libraryJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupAddButton()
        setupEmptyState()

        observeLibrary()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Library"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ui/Activity_Library.kt - UPDATE setupRecyclerView()
    private fun setupRecyclerView() {
        adapter = LibraryAdapter(
            onItemClick = { novelWithStats ->
                val intent = Intent(this, NovelDetailActivity::class.java).apply {
                    putExtra("NOVEL_ID", novelWithStats.id)  // Use id directly from POJO
                    putExtra("NOVEL_NAME", novelWithStats.name)
                }
                startActivity(intent)
            },
            onItemLongClick = { novelWithStats ->
                // Convert back to Novel for deletion/update
                val novel = Novel(id = novelWithStats.id, name = novelWithStats.name)
                showNovelOptionsDialog(novel)
                true
            }
        )

        binding.rvLibrary.layoutManager = LinearLayoutManager(this)
        binding.rvLibrary.adapter = adapter
    }



    private fun setupAddButton() {
        binding.btnAddNovel.setOnClickListener {
            showAddNovelDialog()
        }
    }

    private fun setupEmptyState() {
        binding.tvEmptyLibrary.text = """
            Your library is empty
            
            Start by browsing webnovels and using the
            Summarize button to save summaries here.
            
            Or tap the + button to add a novel manually.
        """.trimIndent()
    }

    private fun observeLibrary() {
        libraryJob = CoroutineScope(Dispatchers.Main).launch {
            viewModel.allNovels.collect { novelList ->  // Now receives NovelWithStats
                updateUI(novelList)
            }
        }
    }
    private fun updateUI(novelList: List<NovelWithStats>) {  // CHANGED PARAMETER TYPE
        if (novelList.isEmpty()) {
            binding.tvEmptyLibrary.isVisible = true
            binding.rvLibrary.isVisible = false
        } else {
            binding.tvEmptyLibrary.isVisible = false
            binding.rvLibrary.isVisible = true
            adapter.submitList(novelList)  // Adapter now expects NovelWithStats
        }
    }

    private fun showAddNovelDialog() {
        val editText = androidx.appcompat.widget.AppCompatEditText(this).apply {
            hint = getString(R.string.hint_novel_name)
            setSingleLine()
        }

        AlertDialog.Builder(this)
            .setTitle("Add New Novel")
            .setMessage("Enter the name of the novel you want to track")
            .setView(editText)
            .setPositiveButton("Add") { _, _ ->
                val novelName = editText.text.toString().trim()
                if (novelName.isNotEmpty()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val existingNovel = viewModel.getNovelByName(novelName)
                        if (existingNovel == null) {
                            viewModel.insertNovel(com.example.novel_summary.data.model.Novel(name = novelName))
                            runOnUiThread {
                                ToastUtils.showSuccess(this@Activity_Library, "Novel added successfully")
                            }
                        } else {
                            runOnUiThread {
                                ToastUtils.showError(this@Activity_Library, "Novel with this name already exists")
                            }
                        }
                    }
                } else {
                    ToastUtils.showError(this, "Please enter a novel name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNovelOptionsDialog(novel: Novel) {
        val options = arrayOf("Edit", "Delete")
        AlertDialog.Builder(this)
            .setTitle("Novel Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditNovelDialog(novel)
                    1 -> showDeleteNovelDialog(novel)
                }
            }
            .show()
    }

    private fun showEditNovelDialog(novel: Novel) {
        val editText = androidx.appcompat.widget.AppCompatEditText(this).apply {
            setText(novel.name)
            hint = getString(R.string.hint_novel_name)
            setSingleLine()
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Novel Name")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val existingNovel = viewModel.getNovelByName(newName)
                        if (existingNovel == null) {
                            viewModel.updateNovel(novel.copy(name = newName))
                            runOnUiThread {
                                ToastUtils.showSuccess(this@Activity_Library, "Novel updated successfully")
                            }
                        } else {
                            runOnUiThread {
                                ToastUtils.showError(this@Activity_Library, "Novel with this name already exists")
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteNovelDialog(novel: Novel) {
        AlertDialog.Builder(this)
            .setTitle("Delete Novel")
            .setMessage("Are you sure you want to delete '${novel.name}' and all its volumes and chapters? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteNovel(novel)
                ToastUtils.showSuccess(this, "Novel deleted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        libraryJob?.cancel()
        super.onDestroy()
    }
}