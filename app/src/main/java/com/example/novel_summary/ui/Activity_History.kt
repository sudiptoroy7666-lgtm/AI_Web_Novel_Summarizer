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
import com.example.novel_summary.data.model.History
import com.example.novel_summary.databinding.ActivityHistoryBinding
import com.example.novel_summary.ui.adapter.HistoryAdapter
import com.example.novel_summary.ui.viewmodel.HistoryViewModel
import com.example.novel_summary.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class Activity_History : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val viewModel: HistoryViewModel by viewModels()

    private lateinit var adapter: HistoryAdapter
    private var historyJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupClearButton()

        observeHistory()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "History"
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
        adapter = HistoryAdapter(
            onItemClick = { history ->
                // FIXED: Navigate to the history URL in MainActivity
                navigateToUrl(history.url)
            },
            onItemLongClick = { history ->
                showDeleteDialog(history)
                true
            }
        )

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

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
                val history = adapter.getItem(position)

                // Show undo snackbar
                showUndoSnackbar(history, position)

                viewModel.deleteHistory(history)
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
        itemTouchHelper.attachToRecyclerView(binding.rvHistory)
    }

    private fun setupClearButton() {
        binding.btnClearHistory.setOnClickListener {
            showClearAllDialog()
        }
    }

    private fun observeHistory() {
        historyJob = lifecycleScope.launch {
            viewModel.getAllHistory().collect { historyList ->
                updateUI(historyList)
            }
        }
    }

    private fun updateUI(historyList: List<History>) {
        if (historyList.isEmpty()) {
            binding.tvEmptyHistory.isVisible = true
            binding.rvHistory.isVisible = false
        } else {
            binding.tvEmptyHistory.isVisible = false
            binding.rvHistory.isVisible = true
            adapter.submitList(historyList)
        }
    }

    private fun showDeleteDialog(history: History) {
        AlertDialog.Builder(this)
            .setTitle("Delete History")
            .setMessage("Are you sure you want to delete this history entry?\n\n${history.title}")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteHistory(history)
                ToastUtils.showSuccess(this, "History deleted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear All History")
            .setMessage("Are you sure you want to delete all browsing history? This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                viewModel.deleteAllHistory()
                ToastUtils.showSuccess(this, "All history cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUndoSnackbar(history: History, position: Int) {
        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            binding.root,
            "History deleted",
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        )

        snackbar.setAction("Undo") {
            viewModel.insertHistory(history)
        }

        snackbar.addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: com.google.android.material.snackbar.Snackbar?, event: Int) {
                super.onDismissed(transientBottomBar, event)
                if (event != DISMISS_EVENT_ACTION) {
                    // If not dismissed by action (undo), actually delete it
                    viewModel.deleteHistory(history)
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
        historyJob?.cancel()
        super.onDestroy()
    }
}