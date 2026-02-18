package com.example.novel_summary.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.novel_summary.R
import com.example.novel_summary.data.model.Chapter
import com.example.novel_summary.data.model.Novel
import com.example.novel_summary.data.model.Volume
import com.example.novel_summary.databinding.ActivitySummaryBinding
import com.example.novel_summary.ui.viewmodel.SummaryViewModel
import com.example.novel_summary.utils.ContentHolder
import com.example.novel_summary.utils.NetworkUtils
import com.example.novel_summary.utils.SummaryPrompts
import com.example.novel_summary.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.*
import android.content.res.Configuration          // Required for Configuration parameter
import android.view.WindowManager                  // Required for window.setSoftInputMode (MainActivity only)

class ActivitySummary : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivitySummaryBinding
    private val viewModel: SummaryViewModel by viewModels()

    private var textToSpeech: TextToSpeech? = null
    private var isSpeaking = false
    private var currentSummaryJob: Job? = null

    // Summary data
    private var extractedContent: String = ""
    private var pageUrl: String = ""
    private var pageTitle: String = ""
    private var currentSummaryType: String = "detailed" // Default
    private var currentSummaryText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupTextToSpeech()
        setupClickListeners()
        extractIntentData()
        loadLastSummaryType() // FIXED: Load last summary type
        validateContent()
    }



    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Summary"
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.summary_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.menu_short_summary -> {
                changeSummaryType("short")
                saveSummaryType("short") // FIXED: Save summary type
                true
            }
            R.id.menu_detailed_summary -> {
                changeSummaryType("detailed")
                saveSummaryType("detailed") // FIXED: Save summary type
                true
            }
            R.id.menu_very_detailed_summary -> {
                changeSummaryType("very_detailed")
                saveSummaryType("very_detailed") // FIXED: Save summary type
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupTextToSpeech() {
        textToSpeech = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                ToastUtils.showError(this, "TTS language not supported")
            }
        } else {
            ToastUtils.showError(this, "TTS initialization failed")
        }
    }

    private fun setupClickListeners() {
        binding.btnTextToSpeech.setOnClickListener {
            toggleTextToSpeech()
        }

        binding.btnSaveSummary.setOnClickListener {
            showSaveSummaryDialog()
        }
    }

    private fun extractIntentData() {
        // ✅ FIXED: Get content from ContentHolder instead of Intent extras
        val contentData = ContentHolder.getContent()

        extractedContent = contentData.content
        pageUrl = contentData.url
        pageTitle = contentData.title

        // Clear ContentHolder after reading to free memory
        ContentHolder.clear()

        binding.tvSummaryTitle.text = pageTitle
    }
    private fun validateContent() {
        if (extractedContent.isEmpty() || extractedContent.length < 100) {
            showErrorAndFinish("No content to summarize. Please try a different page.")
            return
        }

        if (!NetworkUtils.isNetworkAvailable(this)) {
            showErrorAndFinish("No internet connection. Please check your network and try again.")
            return
        }

        generateInitialSummary()
    }

    private fun generateInitialSummary() {


        showLoading(true)

        currentSummaryJob = CoroutineScope(Dispatchers.IO).launch {
            val result = viewModel.generateSummary(extractedContent, currentSummaryType)

            runOnUiThread {
                showLoading(false)
                when {
                    result.isSuccess -> {
                        currentSummaryText = result.getOrNull() ?: ""
                        binding.tvSummaryContent.text = currentSummaryText
                        ToastUtils.showSuccess(this@ActivitySummary, "Summary generated successfully")
                    }
                    result.isFailure -> {
                        handleSummaryError(result.exceptionOrNull())
                    }
                }
            }
        }
    }



    private fun handleSummaryError(error: Throwable?) {
        val errorMessage = when {
            error?.message?.contains("413") == true -> {
                "Content too long! Please try a shorter chapter or select 'Short Summary' option."
            }
            error?.message?.contains("401") == true -> {
                "API authentication failed. Trying fallback key..."
            }
            error?.message?.contains("429") == true -> {
                "Rate limit exceeded. Switching to fallback API key..."
            }
            error?.message?.contains("timeout") == true -> "Request timeout. Please try again."
            error?.message?.contains("network") == true -> "Network error. Check connection."
            else -> "Failed to generate summary: ${error?.message ?: "Unknown error"}"
        }

        // Show specific actionable message
        if (errorMessage.contains("too long")) {
            ToastUtils.showError(this@ActivitySummary, errorMessage)
            // Auto-switch to short summary
            currentSummaryType = "short"
            binding.tvSummaryContent.text = "Switching to SHORT summary to handle large content..."
            generateInitialSummary()
            return
        }

        ToastUtils.showError(this@ActivitySummary, errorMessage)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Only adjust UI layout - DO NOT restart TTS (it continues automatically)
        // Optional: Update button text if needed
        if (isSpeaking) {
            binding.btnTextToSpeech.text = "Stop"
        } else {
            binding.btnTextToSpeech.text = "Text to Speech"
        }
    }


    private fun changeSummaryType(type: String) {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            ToastUtils.showError(this, "No internet connection")
            return
        }

        currentSummaryType = type
        currentSummaryText = ""
        binding.tvSummaryContent.text = "Generating ${SummaryPrompts.getSummaryTypeDescription(type).lowercase()}..."

        generateInitialSummary()
    }

    private fun toggleTextToSpeech() {
        if (currentSummaryText.isEmpty()) {
            ToastUtils.showError(this, "No summary text to speak")
            return
        }

        if (isSpeaking) {
            textToSpeech?.stop()
            isSpeaking = false
            binding.btnTextToSpeech.text = "Text to Speech"
        } else {
            speakText(currentSummaryText)
            isSpeaking = true
            binding.btnTextToSpeech.text = "Stop"
        }
    }

    private fun speakText(text: String) {
        textToSpeech?.setOnUtteranceProgressListener(object :
            android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Speaking started
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = false
                    binding.btnTextToSpeech.text = "Text to Speech"
                }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = false
                    binding.btnTextToSpeech.text = "Text to Speech"
                }
            }
        })

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, this.hashCode().toString())
    }

    private fun showSaveSummaryDialog() {
        if (currentSummaryText.isEmpty()) {
            ToastUtils.showError(this, "No summary to save")
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_save_summary, null)
        val novelEditText = dialogView.findViewById<android.widget.EditText>(R.id.etNovelName)
        val volumeEditText = dialogView.findViewById<android.widget.EditText>(R.id.etVolumeName)
        val chapterEditText = dialogView.findViewById<android.widget.EditText>(R.id.etChapterName)

        // Set default values based on page title
        novelEditText.setText(extractNovelNameFromTitle(pageTitle))
        volumeEditText.setText(extractVolumeFromTitle(pageTitle))
        chapterEditText.setText(pageTitle)

        AlertDialog.Builder(this)
            .setTitle("Save Summary")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val novelName = novelEditText.text.toString().trim()
                val volumeName = volumeEditText.text.toString().trim()
                val chapterName = chapterEditText.text.toString().trim()

                if (novelName.isEmpty() || volumeName.isEmpty() || chapterName.isEmpty()) {
                    ToastUtils.showError(this, "Please fill in all fields")
                    return@setPositiveButton
                }

                showConfirmationDialog(
                    "Save Summary",
                    "Save this summary as:\nNovel: $novelName\nVolume: $volumeName\nChapter: $chapterName"
                ) {
                    saveSummary(novelName, volumeName, chapterName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun extractNovelNameFromTitle(title: String): String {
        // Simple extraction: take first 3-4 words as novel name
        return title.split(" ").take(4).joinToString(" ")
    }

    private fun extractVolumeFromTitle(title: String): String {
        // Look for volume patterns in title
        val volumePatterns = listOf("volume", "vol", "book", "arc")
        volumePatterns.forEach { pattern ->
            if (title.lowercase().contains(pattern)) {
                val parts = title.split(pattern, ignoreCase = true)
                if (parts.size > 1) {
                    return "Volume ${parts[1].trim().split(" ").firstOrNull() ?: "1"}"
                }
            }
        }
        return "Volume 1"
    }

    private fun saveSummary(novelName: String, volumeName: String, chapterName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get or create novel
                var novel = viewModel.getNovelByName(novelName)
                if (novel == null) {
                    val novelId = viewModel.insertNovel(Novel(name = novelName))
                    novel = viewModel.getNovelByName(novelName)
                }

                // Get or create volume
                var volume: Volume? = null
                if (novel != null) {
                    volume = viewModel.getVolumeByName(novel.id, volumeName)
                    if (volume == null) {
                        val volumeId = viewModel.insertVolume(Volume(novelId = novel.id, volumeName = volumeName))
                        volume = viewModel.getVolumeByName(novel.id, volumeName)
                    }
                }

                // Check if chapter already exists
                var chapter: Chapter? = null
                if (volume != null) {
                    chapter = viewModel.getChapterByName(volume.id, chapterName)
                }

                if (chapter != null) {
                    // Update existing chapter
                    viewModel.updateChapter(
                        chapter.copy(
                            summaryText = currentSummaryText,
                            summaryType = currentSummaryType,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    runOnUiThread {
                        ToastUtils.showSuccess(this@ActivitySummary, "Summary updated successfully")
                        finish()
                    }
                } else {
                    // Create new chapter
                    if (volume != null) {
                        val chapterId = viewModel.insertChapter(
                            Chapter(
                                volumeId = volume.id,
                                chapterName = chapterName,
                                summaryText = currentSummaryText,
                                summaryType = currentSummaryType,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        runOnUiThread {
                            ToastUtils.showSuccess(this@ActivitySummary, "Summary saved successfully")
                            finish()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    ToastUtils.showError(this@ActivitySummary, "Failed to save summary: ${e.message}")
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressIndicator.isVisible = show
        binding.tvSummaryContent.isVisible = !show
        binding.btnTextToSpeech.isEnabled = !show
        binding.btnSaveSummary.isEnabled = !show
    }

    private fun showErrorAndFinish(message: String) {
        ToastUtils.showError(this, message)
        finish()
    }

    private fun showConfirmationDialog(
        title: String,
        message: String,
        positiveAction: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Confirm") { _, _ -> positiveAction() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // FIXED: Save summary type to SharedPreferences
    private fun saveSummaryType(type: String) {
        val prefs = getSharedPreferences("SummaryPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_summary_type", type).apply()
    }

    // FIXED: Load last summary type from SharedPreferences
    private fun loadLastSummaryType() {
        val prefs = getSharedPreferences("SummaryPrefs", Context.MODE_PRIVATE)
        currentSummaryType = prefs.getString("last_summary_type", "detailed") ?: "detailed"
    }

    override fun onDestroy() {
        currentSummaryJob?.cancel()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        super.onDestroy()
    }
}