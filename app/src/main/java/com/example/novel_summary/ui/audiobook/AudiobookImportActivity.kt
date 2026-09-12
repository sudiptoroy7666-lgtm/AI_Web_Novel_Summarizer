package com.example.novel_summary.ui.audiobook

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.novel_summary.databinding.ActivityAudiobookImportBinding
import com.example.novel_summary.utils.ToastUtils
import com.example.novel_summary.utils.audiobook.AudiobookStore

class AudiobookImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudiobookImportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAudiobookImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupClickListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Paste Book Text"

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupClickListeners() {
        binding.btnSaveImportedBook.setOnClickListener {
            saveBook()
        }
    }

    private fun saveBook() {
        val title = binding.etAudiobookTitle.text.toString().trim()
        val text = binding.etAudiobookContent.text.toString()

        if (text.trim().length < 100) {
            ToastUtils.showError(
                this,
                "Please paste at least 100 characters"
            )
            return
        }

        binding.btnSaveImportedBook.isEnabled = false

        Thread {
            val result = AudiobookStore.importFromText(
                applicationContext,
                title,
                text
            )

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                binding.btnSaveImportedBook.isEnabled = true

                if (result.isSuccess) {
                    val book = result.getOrNull()

                    ToastUtils.showSuccess(
                        this,
                        "Imported: ${book?.title ?: "Book"}"
                    )

                    finish()
                } else {
                    ToastUtils.showError(
                        this,
                        result.exceptionOrNull()?.message ?: "Import failed"
                    )
                }
            }
        }.start()
    }
}