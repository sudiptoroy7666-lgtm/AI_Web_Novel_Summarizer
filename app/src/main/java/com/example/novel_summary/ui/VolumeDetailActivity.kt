package com.example.novel_summary.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.novel_summary.R
import com.example.novel_summary.data.model.Chapter
import com.example.novel_summary.databinding.ActivityLibraryBinding
import com.example.novel_summary.ui.adapter.ChapterAdapter
import com.example.novel_summary.ui.viewmodel.LibraryViewModel
import com.example.novel_summary.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class VolumeDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private val viewModel: LibraryViewModel by viewModels()

    private var volumeId: Long = 0
    private var volumeName: String = ""
    private var novelId: Long = 0

    private lateinit var adapter: ChapterAdapter
    private var chapterJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        volumeId = intent.getLongExtra("VOLUME_ID", 0)
        volumeName = intent.getStringExtra("VOLUME_NAME") ?: ""
        novelId = intent.getLongExtra("NOVEL_ID", 0)

        setupToolbar()
        setupRecyclerView()
        setupAddButton()

        observeChapters()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = volumeName
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

    private fun setupRecyclerView() {
        adapter = ChapterAdapter(
            onItemClick = { chapter ->
                // Show chapter summary
                val intent = android.content.Intent(this, ChapterDetailActivity::class.java).apply {
                    putExtra("CHAPTER_ID", chapter.id)
                    putExtra("CHAPTER_NAME", chapter.chapterName)
                    putExtra("SUMMARY_TEXT", chapter.summaryText)
                    putExtra("SUMMARY_TYPE", chapter.summaryType)
                }
                startActivity(intent)
            },
            onItemLongClick = { chapter ->
                showChapterOptionsDialog(chapter)
                true
            }
        )

        binding.rvLibrary.layoutManager = LinearLayoutManager(this)
        binding.rvLibrary.adapter = adapter
    }

    private fun setupAddButton() {
        binding.btnAddNovel.setImageResource(android.R.drawable.ic_input_add)
        binding.btnAddNovel.contentDescription = "Add Chapter"
        binding.btnAddNovel.setOnClickListener {
            ToastUtils.showShort(this, "To add a chapter, browse to a webnovel page and use the Summarize button")
        }
    }

    private fun observeChapters() {
        chapterJob = lifecycleScope.launch {
            viewModel.getChaptersByVolumeId(volumeId).collect { chapterList ->
                if (chapterList.isEmpty()) {
                    binding.tvEmptyLibrary.text = getString(R.string.empty_chapters)
                    binding.tvEmptyLibrary.isVisible = true
                    binding.rvLibrary.isVisible = false
                } else {
                    binding.tvEmptyLibrary.isVisible = false
                    binding.rvLibrary.isVisible = true
                    adapter.submitList(chapterList)
                }
            }
        }
    }

    private fun showChapterOptionsDialog(chapter: Chapter) {
        val options = arrayOf("View Summary", "Edit", "Delete")
        AlertDialog.Builder(this)
            .setTitle("Chapter Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = android.content.Intent(this, ChapterDetailActivity::class.java).apply {
                            putExtra("CHAPTER_ID", chapter.id)
                            putExtra("CHAPTER_NAME", chapter.chapterName)
                            putExtra("SUMMARY_TEXT", chapter.summaryText)
                            putExtra("SUMMARY_TYPE", chapter.summaryType)
                        }
                        startActivity(intent)
                    }
                    1 -> showEditChapterDialog(chapter)
                    2 -> showDeleteChapterDialog(chapter)
                }
            }
            .show()
    }

    private fun showEditChapterDialog(chapter: Chapter) {
        val editText = androidx.appcompat.widget.AppCompatEditText(this).apply {
            setText(chapter.chapterName)
            hint = getString(R.string.hint_chapter_name)
            setSingleLine()
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Chapter Name")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    // FIXED: Wrap suspend function in coroutine
                    lifecycleScope.launch(Dispatchers.IO) {
                        val existingChapter = viewModel.getChapterByName(volumeId, newName)
                        if (existingChapter == null) {
                            viewModel.updateChapter(chapter.copy(chapterName = newName))
                            runOnUiThread {
                                ToastUtils.showSuccess(this@VolumeDetailActivity, "Chapter updated successfully")
                            }
                        } else {
                            runOnUiThread {
                                ToastUtils.showError(this@VolumeDetailActivity, "Chapter with this name already exists")
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteChapterDialog(chapter: Chapter) {
        AlertDialog.Builder(this)
            .setTitle("Delete Chapter")
            .setMessage("Are you sure you want to delete '${chapter.chapterName}'?")
            .setPositiveButton("Yes") { _, _ ->
                viewModel.deleteChapter(chapter)
                ToastUtils.showSuccess(this, "Chapter deleted")
            }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onDestroy() {
        chapterJob?.cancel()
        super.onDestroy()
    }
}