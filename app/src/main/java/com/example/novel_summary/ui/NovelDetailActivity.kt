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
import com.example.novel_summary.data.model.Volume
import com.example.novel_summary.databinding.ActivityLibraryBinding
import com.example.novel_summary.ui.adapter.VolumeAdapter
import com.example.novel_summary.ui.viewmodel.LibraryViewModel
import com.example.novel_summary.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class NovelDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private val viewModel: LibraryViewModel by viewModels()

    private var novelId: Long = 0
    private var novelName: String = ""

    private lateinit var adapter: VolumeAdapter
    private var volumeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        novelId = intent.getLongExtra("NOVEL_ID", 0)
        novelName = intent.getStringExtra("NOVEL_NAME") ?: ""

        setupToolbar()
        setupRecyclerView()
        setupAddButton()

        observeVolumes()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = novelName
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

    // ui/NovelDetailActivity.kt - UPDATE setupRecyclerView()
    private fun setupRecyclerView() {
        adapter = VolumeAdapter(
            onItemClick = { volumeWithStats ->
                val intent = Intent(this, VolumeDetailActivity::class.java).apply {
                    putExtra("VOLUME_ID", volumeWithStats.id)
                    putExtra("VOLUME_NAME", volumeWithStats.volumeName)
                    putExtra("NOVEL_ID", novelId)
                }
                startActivity(intent)
            },
            onItemLongClick = { volumeWithStats ->
                // Convert back to Volume for operations
                val volume = Volume(
                    id = volumeWithStats.id,
                    novelId = volumeWithStats.novelId,
                    volumeName = volumeWithStats.volumeName
                )
                showVolumeOptionsDialog(volume)
                true
            }
        )

        binding.rvLibrary.layoutManager = LinearLayoutManager(this)
        binding.rvLibrary.adapter = adapter
    }
    private fun setupAddButton() {
        binding.btnAddNovel.setImageResource(android.R.drawable.ic_input_add)
        binding.btnAddNovel.contentDescription = "Add Volume"
        binding.btnAddNovel.setOnClickListener {
            showAddVolumeDialog()
        }
    }

    private fun observeVolumes() {
        volumeJob = CoroutineScope(Dispatchers.Main).launch {
            viewModel.getVolumesWithStats(novelId).collect { volumeList ->  // Uses new method
                if (volumeList.isEmpty()) {
                    binding.tvEmptyLibrary.text = getString(R.string.empty_volumes)
                    binding.tvEmptyLibrary.isVisible = true
                    binding.rvLibrary.isVisible = false
                } else {
                    binding.tvEmptyLibrary.isVisible = false
                    binding.rvLibrary.isVisible = true

                    // Update adapter type to accept VolumeWithStats
                    (binding.rvLibrary.adapter as? VolumeAdapter)?.submitList(volumeList)
                }
            }
        }
    }


    private fun showAddVolumeDialog() {
        val editText = androidx.appcompat.widget.AppCompatEditText(this).apply {
            hint = getString(R.string.hint_volume_name)
            setSingleLine()
        }

        AlertDialog.Builder(this)
            .setTitle("Add New Volume")
            .setView(editText)
            .setPositiveButton("Add") { _, _ ->
                val volumeName = editText.text.toString().trim()
                if (volumeName.isNotEmpty()) {
                    // FIXED: Wrap suspend function in coroutine
                    CoroutineScope(Dispatchers.IO).launch {
                        viewModel.insertVolume(com.example.novel_summary.data.model.Volume(novelId = novelId, volumeName = volumeName))
                        runOnUiThread {
                            ToastUtils.showSuccess(this@NovelDetailActivity, "Volume added successfully")
                        }
                    }
                } else {
                    ToastUtils.showError(this, "Please enter a volume name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVolumeOptionsDialog(volume: Volume) {
        val options = arrayOf("Edit", "Delete")
        AlertDialog.Builder(this)
            .setTitle("Volume Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditVolumeDialog(volume)
                    1 -> showDeleteVolumeDialog(volume)
                }
            }
            .show()
    }

    private fun showEditVolumeDialog(volume: Volume) {
        val editText = androidx.appcompat.widget.AppCompatEditText(this).apply {
            setText(volume.volumeName)
            hint = getString(R.string.hint_volume_name)
            setSingleLine()
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Volume Name")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    // FIXED: Wrap suspend function in coroutine
                    CoroutineScope(Dispatchers.IO).launch {
                        val existingVolume = viewModel.getVolumeByName(novelId, newName)
                        if (existingVolume == null) {
                            viewModel.updateVolume(volume.copy(volumeName = newName))
                            runOnUiThread {
                                ToastUtils.showSuccess(this@NovelDetailActivity, "Volume updated successfully")
                            }
                        } else {
                            runOnUiThread {
                                ToastUtils.showError(this@NovelDetailActivity, "Volume with this name already exists")
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteVolumeDialog(volume: Volume) {
        AlertDialog.Builder(this)
            .setTitle("Delete Volume")
            .setMessage("Are you sure you want to delete '${volume.volumeName}' and all its chapters?")
            .setPositiveButton("Yes") { _, _ ->
                viewModel.deleteVolume(volume)
                ToastUtils.showSuccess(this, "Volume deleted")
            }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onDestroy() {
        volumeJob?.cancel()
        super.onDestroy()
    }
}