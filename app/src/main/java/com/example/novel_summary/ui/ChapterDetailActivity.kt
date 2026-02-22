package com.example.novel_summary.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.novel_summary.R
import com.example.novel_summary.databinding.ActivitySummaryBinding
import com.example.novel_summary.utils.ToastUtils
import java.util.*

class ChapterDetailActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivitySummaryBinding
    private var textToSpeech: TextToSpeech? = null
    private var isSpeaking = false
    private var isTtsReady = false
    private var summaryText: String = ""
    private var ttsInstallPromptShown = false

    // ✅ CHUNKING VARIABLES
    private var textChunks: List<String> = emptyList()
    private var currentChunkIndex = 0
    private val MAX_CHUNK_SIZE = 4000 // Safe limit for most TTS engines

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupTextToSpeech()
        setupUI()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.btnTextToSpeech.isVisible = true
        binding.btnSaveSummary.isVisible = false
        binding.btnSummaryMenu.isVisible = false
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

    private fun setupTextToSpeech() {
        binding.btnTextToSpeech.isEnabled = false
        binding.btnTextToSpeech.alpha = 0.5f
        binding.btnTextToSpeech.text = "Initializing TTS..."
        textToSpeech = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        when (status) {
            TextToSpeech.SUCCESS -> {
                val result = textToSpeech?.setLanguage(Locale.US)
                when (result) {
                    TextToSpeech.LANG_MISSING_DATA -> {
                        promptInstallTtsData()
                        isTtsReady = false
                    }
                    TextToSpeech.LANG_NOT_SUPPORTED -> {
                        showError("English language not supported")
                        isTtsReady = false
                    }
                    else -> {
                        isTtsReady = true
                        runOnUiThread {
                            binding.btnTextToSpeech.isEnabled = true
                            binding.btnTextToSpeech.alpha = 1.0f
                            binding.btnTextToSpeech.text = "Text to Speech"
                        }
                    }
                }
            }
            else -> {
                showError("TTS initialization failed")
                isTtsReady = false
            }
        }
    }

    private fun setupUI() {
        val chapterName = intent.getStringExtra("CHAPTER_NAME") ?: "Untitled Chapter"
        summaryText = intent.getStringExtra("SUMMARY_TEXT")?.trim() ?: ""
        val summaryType = intent.getStringExtra("SUMMARY_TYPE") ?: "summary"

        binding.tvSummaryTitle.text = chapterName
        binding.tvSummaryContent.text = summaryText.ifEmpty { "No summary available" }

        // ✅ Show text length indicator
        if (summaryText.length > 4000) {
            binding.btnTextToSpeech.text = "Text to Speech (${summaryText.length} chars)"
        }

        binding.btnTextToSpeech.setOnClickListener {
            if (!isTtsReady) {
                ToastUtils.showError(this, "TTS engine still initializing. Please wait.")
                return@setOnClickListener
            }

            if (summaryText.isBlank()) {
                ToastUtils.showError(this, "No text available to speak")
                return@setOnClickListener
            }

            toggleSpeech()
        }
    }

    private fun toggleSpeech() {
        if (isSpeaking) {
            stopSpeaking()
        } else {
            startSpeaking()
        }
    }

    // ✅ METHOD 1: CHUNKING WITH SEQUENTIAL PLAYBACK
    private fun startSpeaking() {
        if (summaryText.length < 10) {
            ToastUtils.showError(this, "Text too short to speak")
            return
        }

        // Split text into chunks
        textChunks = splitTextIntoChunks(summaryText, MAX_CHUNK_SIZE)
        currentChunkIndex = 0

        // Set up progress listener
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = true
                    binding.btnTextToSpeech.text = "❚❚ Stop (${currentChunkIndex + 1}/${textChunks.size})"
                }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    currentChunkIndex++
                    if (currentChunkIndex < textChunks.size) {
                        // Play next chunk
                        speakNextChunk()
                    } else {
                        // All chunks done
                        isSpeaking = false
                        binding.btnTextToSpeech.text = "Text to Speech"
                        ToastUtils.showSuccess(this@ChapterDetailActivity, "Finished speaking")
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = false
                    binding.btnTextToSpeech.text = "Text to Speech"
                    ToastUtils.showError(this@ChapterDetailActivity, "Speech error occurred")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?, errorCode: Int) {
                android.util.Log.e("TTS_ERROR", "TTS error $errorCode")
                onError(utteranceId)
            }
        })

        // Start with first chunk
        speakNextChunk()
    }

    private fun speakNextChunk() {
        if (currentChunkIndex >= textChunks.size) return

        val chunk = textChunks[currentChunkIndex]
        val utteranceId = "tts_chunk_${currentChunkIndex}_${System.currentTimeMillis()}"

        val result = textToSpeech?.speak(
            chunk,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )

        if (result == TextToSpeech.ERROR) {
            runOnUiThread {
                ToastUtils.showError(this@ChapterDetailActivity, "Failed to start speech")
                isSpeaking = false
                binding.btnTextToSpeech.text = "Text to Speech"
            }
        }
    }

    private fun stopSpeaking() {
        textToSpeech?.stop()
        isSpeaking = false
        binding.btnTextToSpeech.text = "Text to Speech"
    }

    private fun splitTextIntoChunks(text: String, maxSize: Int): List<String> {
        val chunks = mutableListOf<String>()
        var position = 0

        while (position < text.length) {
            val end = (position + maxSize).coerceAtMost(text.length)

            // Try to split at sentence boundary for natural breaks
            var splitPoint = end
            if (end < text.length) {
                val substring = text.substring(position, end)
                val lastPeriod = substring.lastIndexOf('.')
                val lastExclamation = substring.lastIndexOf('!')
                val lastQuestion = substring.lastIndexOf('?')

                val lastSentenceEnd = maxOf(lastPeriod, lastExclamation, lastQuestion)
                if (lastSentenceEnd > maxSize / 2) { // Only split if we have a reasonable chunk
                    splitPoint = position + lastSentenceEnd + 1
                }
            }

            chunks.add(text.substring(position, splitPoint).trim())
            position = splitPoint
        }

        return chunks
    }

    private fun showError(message: String) {
        runOnUiThread {
            ToastUtils.showError(this@ChapterDetailActivity, message)
            binding.btnTextToSpeech.isEnabled = false
            binding.btnTextToSpeech.alpha = 0.5f
            binding.btnTextToSpeech.text = "TTS Unavailable"
        }
    }

    private fun promptInstallTtsData() {
        if (ttsInstallPromptShown || isFinishing) return
        ttsInstallPromptShown = true

        AlertDialog.Builder(this)
            .setTitle("Voice Data Required")
            .setMessage("Text-to-speech requires English voice data. Would you like to install it now?")
            .setPositiveButton("Install") { _, _ ->
                val installIntent = Intent()
                installIntent.action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                startActivity(installIntent)
            }
            .setNegativeButton("Cancel") { _, _ ->
                runOnUiThread {
                    binding.btnTextToSpeech.isEnabled = false
                    binding.btnTextToSpeech.alpha = 0.5f
                    binding.btnTextToSpeech.text = "TTS Unavailable"
                }
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        textToSpeech?.apply {
            stop()
            setOnUtteranceProgressListener(null)
            shutdown()
        }
        textToSpeech = null
        super.onDestroy()
    }
}