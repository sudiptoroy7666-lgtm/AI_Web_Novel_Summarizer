package com.example.novel_summary.ui.audiobook

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.novel_summary.databinding.ActivityAudiobookPlayerBinding
import com.example.novel_summary.service.AudiobookTtsService
import com.example.novel_summary.utils.ToastUtils
import com.example.novel_summary.utils.audiobook.AudiobookPlayerPrefs
import java.util.Locale

class AudiobookPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudiobookPlayerBinding

    private var bookId: String? = null
    private var bookTitle: String? = null

    private var service: AudiobookTtsService? = null
    private var bound = false

    private var totalUnits = 0
    private var isSeeking = false
    private var lastDisplayedUnitIndex = -1

    private val speedOptions = floatArrayOf(
        0.75f,
        1.0f,
        1.25f,
        1.5f,
        2.0f
    )

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as AudiobookTtsService.LocalBinder
            service = localBinder.getService()
            bound = true

            service?.setListener(serviceListener)

            val id = bookId ?: return

            val currentServiceBookId = service?.getBookId()
            val currentState = service?.getState()

            if (currentServiceBookId != id || currentState == AudiobookTtsService.State.IDLE) {
                service?.loadBook(id)
            } else {
                updateUiFromService()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val serviceListener = object : AudiobookTtsService.Listener {

        override fun onStateChanged(state: AudiobookTtsService.State) {
            updatePlaybackControls()
        }

        override fun onBookLoaded(totalUnits: Int, currentUnitIndex: Int) {
            this@AudiobookPlayerActivity.totalUnits = totalUnits

            binding.seekBar.max = maxOf(0, totalUnits - 1)
            binding.seekBar.progress = currentUnitIndex

            lastDisplayedUnitIndex = -1

            updateCurrentText(currentUnitIndex)
            updatePositionLabel(currentUnitIndex)
            updatePlaybackControls()
            updateSpeedButton()
        }

        override fun onPositionChanged(unitIndex: Int, charOffset: Int) {
            if (!isSeeking && binding.seekBar.progress != unitIndex) {
                binding.seekBar.progress = unitIndex
            }

            updateCurrentText(unitIndex)
            updatePositionLabel(unitIndex)
        }

        override fun onError(message: String) {
            ToastUtils.showError(this@AudiobookPlayerActivity, message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAudiobookPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId = intent.getStringExtra("BOOK_ID")
        bookTitle = intent.getStringExtra("BOOK_TITLE")

        if (bookId == null) {
            ToastUtils.showError(this, "Book not found")
            finish()
            return
        }

        setupToolbar()
        setupClickListeners()
        setupSeekBar()

        requestNotificationPermission()

        bindTtsService()
    }

    override fun onDestroy() {
        service?.setListener(null)

        if (bound) {
            try {
                unbindService(connection)
            } catch (e: Exception) {
                // Ignore
            }
            bound = false
        }

        if (!isChangingConfigurations) {
            service?.stopServiceIfNotPlaying()
        }

        service = null

        super.onDestroy()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = bookTitle ?: "Audiobook"

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupClickListeners() {
        binding.btnPlayPause.setOnClickListener {
            service?.togglePlayPause()
        }

        binding.btnSkipBack.setOnClickListener {
            service?.skip(-10)
        }

        binding.btnSkipForward.setOnClickListener {
            service?.skip(10)
        }

        binding.btnPlaybackSpeed.setOnClickListener {
            cyclePlaybackSpeed()
        }

        binding.btnResetPosition.setOnClickListener {
            resetPosition()
        }
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updatePositionLabel(progress)
                    showSeekPreview(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false

                binding.tvSeekPreview.visibility = View.GONE

                val progress = seekBar?.progress ?: 0

                service?.seekToUnit(progress, 0)
            }
        })
    }

    private fun bindTtsService() {
        val intent = Intent(this, AudiobookTtsService::class.java)

        startService(intent)

        bindService(
            intent,
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun updateUiFromService() {
        val currentService = service ?: return

        totalUnits = currentService.getTotalUnits()

        if (totalUnits > 0) {
            binding.seekBar.max = maxOf(0, totalUnits - 1)
            binding.seekBar.progress = currentService.getCurrentUnitIndex()

            lastDisplayedUnitIndex = -1

            updateCurrentText(currentService.getCurrentUnitIndex())
            updatePositionLabel(currentService.getCurrentUnitIndex())
        }

        updatePlaybackControls()
        updateSpeedButton()
    }

    private fun updatePlaybackControls() {
        val currentState = service?.getState() ?: AudiobookTtsService.State.IDLE

        binding.btnPlayPause.isEnabled =
            totalUnits > 0 && currentState != AudiobookTtsService.State.LOADING

        binding.btnPlaybackSpeed.isEnabled = service != null

        binding.btnResetPosition.isEnabled =
            totalUnits > 0 && currentState != AudiobookTtsService.State.LOADING

        binding.btnPlayPause.text =
            if (currentState == AudiobookTtsService.State.PLAYING) "Pause" else "Play"

        binding.tvPlaybackStatus.text = when (currentState) {
            AudiobookTtsService.State.LOADING -> "Loading..."
            AudiobookTtsService.State.READY -> "Ready"
            AudiobookTtsService.State.PLAYING -> "Playing"
            AudiobookTtsService.State.PAUSED -> "Paused"
            AudiobookTtsService.State.FINISHED -> "Finished"
            AudiobookTtsService.State.IDLE -> "Idle"
        }
    }

    private fun updateCurrentText(unitIndex: Int) {
        if (unitIndex == lastDisplayedUnitIndex) return

        val text = service?.getCurrentUnitText() ?: ""

        binding.tvCurrentText.text = text

        lastDisplayedUnitIndex = unitIndex
    }

    private fun updatePositionLabel(unitIndex: Int) {
        val total = maxOf(totalUnits, 1)
        binding.tvSeekBarLabel.text = "Part ${unitIndex + 1} / $total"
    }

    private fun updateSpeedButton() {
        val speed = service?.getPlaybackSpeed()
            ?: AudiobookPlayerPrefs.getPlaybackSpeed(this)

        binding.btnPlaybackSpeed.text = String.format(
            Locale.getDefault(),
            "Speed: %.2fx",
            speed
        )
    }

    private fun cyclePlaybackSpeed() {
        val currentSpeed = service?.getPlaybackSpeed()
            ?: AudiobookPlayerPrefs.getPlaybackSpeed(this)

        val nextSpeed = speedOptions.firstOrNull { it > currentSpeed + 0.001f }
            ?: speedOptions.first()

        service?.setPlaybackSpeed(nextSpeed)

        updateSpeedButton()

        ToastUtils.showShort(
            this,
            String.format(Locale.getDefault(), "Playback speed: %.2fx", nextSpeed)
        )
    }

    private fun resetPosition() {
        service?.seekToUnit(0, 0)

        binding.seekBar.progress = 0

        updatePositionLabel(0)

        ToastUtils.showShort(this, "Restarted from beginning")
    }

    private fun showSeekPreview(progress: Int) {
        val unitText = service?.getUnitText(progress) ?: ""

        if (unitText.isBlank()) {
            binding.tvSeekPreview.visibility = View.GONE
            return
        }

        val preview = if (unitText.length <= 120) {
            unitText
        } else {
            unitText.take(120) + "..."
        }

        binding.tvSeekPreview.text = preview
        binding.tvSeekPreview.visibility = View.VISIBLE
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS

            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(permission),
                    1001
                )
            }
        }
    }
}