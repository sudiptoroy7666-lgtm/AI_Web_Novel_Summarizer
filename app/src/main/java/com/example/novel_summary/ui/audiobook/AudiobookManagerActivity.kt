package com.example.novel_summary.ui.audiobook
import com.example.novel_summary.utils.audiobook.AudiobookPositionStore
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.novel_summary.R
import com.example.novel_summary.databinding.ActivityAudiobookManagerBinding
import com.example.novel_summary.databinding.ItemAudiobookBinding
import com.example.novel_summary.utils.ToastUtils
import com.example.novel_summary.utils.audiobook.AudiobookMeta
import com.example.novel_summary.utils.audiobook.AudiobookStore

class AudiobookManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudiobookManagerBinding

    private val adapter = AudiobookAdapter(
        onClick = { book ->
            onBookClicked(book)
        },
        onDelete = { book ->
            confirmDeleteBook(book)
        }
    )

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAudiobookManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshBooks()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Audiobooks"

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        binding.rvAudiobooks.layoutManager = LinearLayoutManager(this)
        binding.rvAudiobooks.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnPasteText.setOnClickListener {
            startActivity(Intent(this, AudiobookImportActivity::class.java))
        }

        binding.btnLoadTextFile.setOnClickListener {
            openFilePicker()
        }
    }

    private fun refreshBooks() {
        val books = AudiobookStore.getBooks(this)

        adapter.submitList(books)

        binding.tvEmptyAudiobooks.isVisible = books.isEmpty()
        binding.rvAudiobooks.isVisible = books.isNotEmpty()
    }

    private fun openFilePicker() {
        try {
            filePickerLauncher.launch(
                arrayOf(
                    "text/plain",
                    "text/*",
                    "*/*"
                )
            )
        } catch (e: Exception) {
            ToastUtils.showError(this, "No file picker available on this device")
        }
    }

    private fun importFile(uri: Uri) {
        ToastUtils.showShort(this, "Importing file...")

        Thread {
            val result = AudiobookStore.importFromUri(applicationContext, uri)

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                if (result.isSuccess) {
                    val book = result.getOrNull()
                    ToastUtils.showSuccess(
                        this,
                        "Imported: ${book?.title ?: "Book"}"
                    )
                    refreshBooks()
                } else {
                    ToastUtils.showError(
                        this,
                        result.exceptionOrNull()?.message ?: "Import failed"
                    )
                }
            }
        }.start()
    }

    private fun onBookClicked(book: AudiobookMeta) {
        val intent = Intent(this, AudiobookPlayerActivity::class.java)
        intent.putExtra("BOOK_ID", book.id)
        intent.putExtra("BOOK_TITLE", book.title)
        startActivity(intent)
    }

    private fun confirmDeleteBook(book: AudiobookMeta) {
        AlertDialog.Builder(this)
            .setTitle("Delete Audiobook")
            .setMessage("Delete \"${book.title}\"?\n\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                AudiobookStore.deleteBook(applicationContext, book.id)
                ToastUtils.showSuccess(this, "Audiobook deleted")
                refreshBooks()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class AudiobookAdapter(
        private val onClick: (AudiobookMeta) -> Unit,
        private val onDelete: (AudiobookMeta) -> Unit
    ) : RecyclerView.Adapter<AudiobookAdapter.AudiobookViewHolder>() {

        private val items = mutableListOf<AudiobookMeta>()

        fun submitList(newItems: List<AudiobookMeta>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudiobookViewHolder {
            val binding = ItemAudiobookBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return AudiobookViewHolder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: AudiobookViewHolder, position: Int) {
            val book = items[position]

            val context = holder.binding.root.context

            val savedPosition = AudiobookPositionStore.getPosition(context, book.id)

            val baseDetails = AudiobookStore.getDetails(book)

            val details = if (savedPosition.first > 0 || savedPosition.second > 0) {
                "$baseDetails • Last part ${savedPosition.first + 1}"
            } else {
                baseDetails
            }

            holder.binding.tvAudiobookTitle.text = book.title
            holder.binding.tvAudiobookDetails.text = details

            holder.binding.root.setOnClickListener {
                onClick(book)
            }

            holder.binding.btnDeleteAudiobook.setOnClickListener {
                onDelete(book)
            }
        }

        class AudiobookViewHolder(
            val binding: ItemAudiobookBinding
        ) : RecyclerView.ViewHolder(binding.root)
    }
}