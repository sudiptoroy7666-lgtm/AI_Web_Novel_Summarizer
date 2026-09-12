package com.example.novel_summary.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.novel_summary.R
import com.example.novel_summary.data.model.Bookmark
import com.example.novel_summary.databinding.ActivityBoolmarksBinding
import com.example.novel_summary.ui.adapter.BookmarkAdapter
import com.example.novel_summary.ui.viewmodel.BookmarkViewModel
import com.example.novel_summary.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class Activity_Boolmarks : AppCompatActivity() {

    private lateinit var binding: ActivityBoolmarksBinding
    private val viewModel: BookmarkViewModel by viewModels()

    private lateinit var adapter: BookmarkAdapter
    private var bookmarkJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBoolmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupClearButton()

        observeBookmarks()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Bookmarks"
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
        adapter = BookmarkAdapter(
            onItemClick = { bookmark ->
                // FIXED: Navigate to the bookmarked URL in MainActivity
                navigateToUrl(bookmark.url)
            },
            onItemLongClick = { bookmark ->
                showDeleteDialog(bookmark)
                true
            }
        )

        binding.rvBookmarks.layoutManager = LinearLayoutManager(this)
        binding.rvBookmarks.adapter = adapter

        // Swipe to delete with visual feedback
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val bookmark = adapter.getItem(position)

                // Show undo snackbar
                showUndoSnackbar(bookmark, position)

                viewModel.deleteBookmark(bookmark)
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                // Add visual feedback during swipe
                val itemView = viewHolder.itemView
                val background = android.graphics.drawable.ColorDrawable(
                    if (dX > 0) android.graphics.Color.parseColor("#FF4444") // Right swipe
                    else android.graphics.Color.parseColor("#FF4444") // Left swipe
                )
                background.setBounds(
                    itemView.left,
                    itemView.top,
                    itemView.right,
                    itemView.bottom
                )
                background.draw(c)
            }
        }

        val itemTouchHelper = ItemTouchHelper(swipeHandler)
        itemTouchHelper.attachToRecyclerView(binding.rvBookmarks)
    }

    private fun setupClearButton() {
        binding.btnClearBookmarks.setOnClickListener {
            showClearAllDialog()
        }
    }

    private fun observeBookmarks() {
        bookmarkJob = lifecycleScope.launch {
            viewModel.getAllBookmarks().collect { bookmarkList ->
                updateUI(bookmarkList)
            }
        }
    }

    private fun updateUI(bookmarkList: List<Bookmark>) {
        if (bookmarkList.isEmpty()) {
            binding.tvEmptyBookmarks.isVisible = true
            binding.rvBookmarks.isVisible = false
        } else {
            binding.tvEmptyBookmarks.isVisible = false
            binding.rvBookmarks.isVisible = true
            adapter.submitList(bookmarkList)
        }
    }

    private fun showDeleteDialog(bookmark: Bookmark) {
        AlertDialog.Builder(this)
            .setTitle("Delete Bookmark")
            .setMessage("Are you sure you want to delete this bookmark?\n\n${bookmark.title}")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteBookmark(bookmark)
                ToastUtils.showSuccess(this, "Bookmark deleted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Bookmarks")
            .setMessage("Are you sure you want to delete all bookmarks? This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                viewModel.deleteAllBookmarks()
                ToastUtils.showSuccess(this, "All bookmarks cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUndoSnackbar(bookmark: Bookmark, position: Int) {
        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            binding.root,
            "Bookmark deleted",
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        )

        snackbar.setAction("Undo") {
            viewModel.insertBookmark(bookmark)
        }

        snackbar.addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: com.google.android.material.snackbar.Snackbar?, event: Int) {
                super.onDismissed(transientBottomBar, event)
                if (event != DISMISS_EVENT_ACTION) {
                    // If not dismissed by action (undo), actually delete it
                    viewModel.deleteBookmark(bookmark)
                }
            }
        })

        snackbar.show()
    }

    // FIXED: Navigate to URL in MainActivity
    private fun navigateToUrl(url: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("SELECTED_URL", url)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        bookmarkJob?.cancel()
        super.onDestroy()
    }
}