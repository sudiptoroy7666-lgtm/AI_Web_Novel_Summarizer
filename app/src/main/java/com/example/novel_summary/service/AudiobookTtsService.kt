package com.example.novel_summary.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.novel_summary.ui.audiobook.AudiobookPlayerActivity
import com.example.novel_summary.utils.audiobook.AudiobookPositionStore
import com.example.novel_summary.utils.audiobook.AudiobookStore
import com.example.novel_summary.utils.audiobook.SpeechUnitSplitter
import java.util.Locale
import com.example.novel_summary.utils.audiobook.AudiobookPlayerPrefs
class AudiobookTtsService : Service(), TextToSpeech.OnInitListener {

    enum class State {
        IDLE,
        LOADING,
        READY,
        PLAYING,
        PAUSED,
        FINISHED
    }

    interface Listener {
        fun onStateChanged(state: State)
        fun onBookLoaded(totalUnits: Int, currentUnitIndex: Int)
        fun onPositionChanged(unitIndex: Int, charOffset: Int)
        fun onError(message: String)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.example.novel_summary.ACTION_PLAY_PAUSE"
        const val ACTION_STOP = "com.example.novel_summary.ACTION_STOP"

        private const val CHANNEL_ID = "audiobook_tts_channel"
        private const val CHANNEL_NAME = "Audiobook Playback"
        private const val NOTIFICATION_ID = 2001
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudiobookTtsService = this@AudiobookTtsService
    }

    private val binder = LocalBinder()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingPlay = false

    private var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var units: List<String> = emptyList()

    private var bookId: String? = null
    private var bookTitle: String? = null

    private var currentIndex = 0
    private var currentCharOffset = 0
    private var utteranceBaseOffset = 0

    private var state: State = State.IDLE

    private var currentUtteranceId: String? = null

    private var lastPositionSaveTime = 0L

    private var audioFocusRequest: AudioFocusRequest? = null
    private var playbackSpeed = 1.0f
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        tts = TextToSpeech(this, this)
    }

    override fun onDestroy() {
        try {
            stopPlaybackSafely()
            tts?.shutdown()
        } catch (e: Exception) {
            // Ignore
        }

        abandonAudioFocus()

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_STOP -> stopServiceCompletely()
        }

        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (state != State.PLAYING) {
            stopServiceCompletely()
        }

        super.onTaskRemoved(rootIntent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts?.language = Locale.US

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // No action needed
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != currentUtteranceId) return

                        mainHandler.post {
                            handleUtteranceDone()
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            handleUtteranceError()
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post {
                            handleUtteranceError()
                        }
                    }

                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        if (utteranceId != currentUtteranceId) return

                        mainHandler.post {
                            handleRangeStart(start)
                        }
                    }
                })

                ttsReady = true


                playbackSpeed = AudiobookPlayerPrefs.getPlaybackSpeed(applicationContext)
                applyPlaybackSpeed()

                if (pendingPlay && state != State.PLAYING) {
                    pendingPlay = false
                    play()
                }
            } catch (e: Exception) {
                ttsReady = false
                notifyError("Text-to-speech setup failed")
            }
        } else {
            ttsReady = false
            notifyError("Text-to-speech initialization failed")
        }
    }

    fun setListener(newListener: Listener?) {
        listener = newListener

        val currentListener = listener ?: return

        mainHandler.post {
            currentListener.onStateChanged(state)

            if (units.isNotEmpty()) {
                currentListener.onBookLoaded(units.size, currentIndex)
                currentListener.onPositionChanged(currentIndex, currentCharOffset)
            }
        }
    }

    fun loadBook(newBookId: String) {
        if (bookId == newBookId && units.isNotEmpty() && state != State.IDLE) {
            mainHandler.post {
                listener?.onBookLoaded(units.size, currentIndex)
                listener?.onStateChanged(state)
                listener?.onPositionChanged(currentIndex, currentCharOffset)
            }
            return
        }

        try {
            tts?.stop()
        } catch (e: Exception) {
            // Ignore
        }

        bookId = newBookId
        bookTitle = null
        units = emptyList()
        currentIndex = 0
        currentCharOffset = 0
        utteranceBaseOffset = 0

        setState(State.LOADING)

        Thread {
            val appContext = applicationContext
            playbackSpeed = AudiobookPlayerPrefs.getPlaybackSpeed(appContext)
            val meta = AudiobookStore.getBooks(appContext)
                .firstOrNull { it.id == newBookId }

            if (meta == null) {
                mainHandler.post {
                    notifyError("Book not found")
                    setState(State.IDLE)
                }
                return@Thread
            }

            val text = AudiobookStore.readText(appContext, meta)

            if (text == null) {
                mainHandler.post {
                    notifyError("Could not read book file")
                    setState(State.IDLE)
                }
                return@Thread
            }

            val splitUnits = SpeechUnitSplitter.split(text)

            if (splitUnits.isEmpty()) {
                mainHandler.post {
                    notifyError("No readable text found")
                    setState(State.IDLE)
                }
                return@Thread
            }

            val savedPosition = AudiobookPositionStore.getPosition(appContext, newBookId)

            val savedUnitIndex = savedPosition.first
            val savedCharOffset = savedPosition.second

            bookTitle = meta.title
            units = splitUnits

            currentIndex = savedUnitIndex.coerceIn(0, splitUnits.size - 1)

            currentCharOffset = if (currentIndex < splitUnits.size) {
                savedCharOffset.coerceIn(0, splitUnits[currentIndex].length)
            } else {
                0
            }

            setState(State.READY)

            mainHandler.post {
                listener?.onBookLoaded(units.size, currentIndex)
                listener?.onPositionChanged(currentIndex, currentCharOffset)
            }
        }.start()
    }

    fun play() {
        if (units.isEmpty()) return

        if (!ttsReady) {
            pendingPlay = true
            notifyError("Text-to-speech is still initializing")
            return
        }

        pendingPlay = false

        if (state == State.FINISHED || currentIndex >= units.size) {
            currentIndex = 0
            currentCharOffset = 0
        }

        requestAudioFocus()

        setState(State.PLAYING)

        speakCurrentUnit()
    }

    fun pause() {
        if (state != State.PLAYING) return

        stopPlaybackSafely()
        savePosition(true)

        setState(State.PAUSED)
    }

    fun togglePlayPause() {
        if (state == State.PLAYING) {
            pause()
        } else {
            play()
        }
    }

    fun seekToUnit(unitIndex: Int, charOffset: Int = 0) {
        if (units.isEmpty()) return

        currentIndex = unitIndex.coerceIn(0, units.size - 1)

        val unitLength = units[currentIndex].length

        currentCharOffset = charOffset.coerceIn(0, unitLength)

        savePosition(true)

        notifyPosition()

        if (state == State.PLAYING) {
            speakCurrentUnit()
        } else if (state == State.FINISHED) {
            setState(State.READY)
        }
    }

    fun skip(deltaUnits: Int) {
        if (units.isEmpty()) return

        seekToUnit(currentIndex + deltaUnits, 0)
    }

    fun getState(): State = state

    fun getBookId(): String? = bookId

    fun getTotalUnits(): Int = units.size

    fun getCurrentUnitIndex(): Int = currentIndex

    fun getCurrentUnitText(): String {
        if (units.isEmpty()) return ""
        if (currentIndex < 0 || currentIndex >= units.size) return ""

        return units[currentIndex]
    }

    fun stopServiceIfNotPlaying() {
        if (state != State.PLAYING) {
            stopServiceCompletely()
        }
    }

    fun stopServiceCompletely() {
        stopPlaybackSafely()
        abandonAudioFocus()

        state = State.IDLE
        bookId = null
        bookTitle = null
        units = emptyList()
        currentIndex = 0
        currentCharOffset = 0
        utteranceBaseOffset = 0
        currentUtteranceId = null

        try {
            stopForeground(true)
        } catch (e: Exception) {
            // Ignore
        }

        mainHandler.post {
            listener?.onStateChanged(state)
        }

        stopSelf()
    }
    fun getPlaybackSpeed(): Float = playbackSpeed

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.5f, 3.0f)

        AudiobookPlayerPrefs.savePlaybackSpeed(applicationContext, playbackSpeed)

        applyPlaybackSpeed()

        if (state == State.PLAYING) {
            speakCurrentUnit()
        }
    }

    fun getUnitText(index: Int): String {
        if (index < 0 || index >= units.size) return ""
        return units[index]
    }

    private fun applyPlaybackSpeed() {
        try {
            tts?.setSpeechRate(playbackSpeed)
        } catch (e: Exception) {
            // Ignore
        }
    }
    private fun speakCurrentUnit() {
        if (!ttsReady || units.isEmpty()) return

        if (currentIndex >= units.size) {
            setState(State.FINISHED)
            return
        }

        val unit = units[currentIndex]

        if (unit.isBlank()) {
            currentIndex++
            currentCharOffset = 0
            savePosition(true)
            speakCurrentUnit()
            return
        }

        val safeOffset = currentCharOffset.coerceIn(0, unit.length)

        utteranceBaseOffset = safeOffset

        val textToSpeak = if (safeOffset < unit.length) {
            unit.substring(safeOffset)
        } else {
            ""
        }

        if (textToSpeak.isBlank()) {
            currentIndex++
            currentCharOffset = 0
            savePosition(true)
            speakCurrentUnit()
            return
        }
        applyPlaybackSpeed()
        currentUtteranceId = "unit_${currentIndex}_${System.currentTimeMillis()}"

        try {
            tts?.speak(
                textToSpeak,
                TextToSpeech.QUEUE_FLUSH,
                null,
                currentUtteranceId
            )
        } catch (e: Exception) {
            notifyError("Speech failed")
            setState(State.PAUSED)
            return
        }

        savePosition(true)
        notifyPosition()
    }

    private fun handleUtteranceDone() {
        if (state != State.PLAYING) return

        currentUtteranceId = null
        currentIndex++
        currentCharOffset = 0
        utteranceBaseOffset = 0

        savePosition(true)
        notifyPosition()

        if (currentIndex < units.size) {
            speakCurrentUnit()
        } else {
            setState(State.FINISHED)
        }
    }

    private fun handleUtteranceError() {
        if (state != State.PLAYING) return

        currentUtteranceId = null
        savePosition(true)

        notifyError("Speech error")

        setState(State.PAUSED)
    }

    private fun handleRangeStart(start: Int) {
        if (state != State.PLAYING) return
        if (units.isEmpty()) return
        if (currentIndex < 0 || currentIndex >= units.size) return

        val unitLength = units[currentIndex].length

        currentCharOffset = (utteranceBaseOffset + start).coerceIn(0, unitLength)

        savePosition(false)
        notifyPosition()
    }

    private fun stopPlaybackSafely() {
        try {
            currentUtteranceId = null
            tts?.stop()
        } catch (_: Exception) {
            // Ignore interruption during shutdown.
        }
    }

    private fun setState(newState: State) {
        state = newState

        when (state) {
            State.PLAYING -> startForegroundNotification()
            State.PAUSED -> updateNotification()
            State.FINISHED -> {
                try {
                    stopForeground(false)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            State.IDLE -> {
                try {
                    stopForeground(false)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            State.READY -> {
                // No notification needed until playback starts
            }
            State.LOADING -> {
                // No notification needed while loading
            }
        }

        mainHandler.post {
            listener?.onStateChanged(state)
        }
    }

    private fun savePosition(important: Boolean) {
        val currentBookId = bookId ?: return
        if (units.isEmpty()) return

        val now = System.currentTimeMillis()

        if (important || now - lastPositionSaveTime > 1000L) {
            AudiobookPositionStore.savePosition(
                applicationContext,
                currentBookId,
                currentIndex,
                currentCharOffset,
                important
            )

            lastPositionSaveTime = now
        }
    }

    private fun notifyPosition() {
        mainHandler.post {
            listener?.onPositionChanged(currentIndex, currentCharOffset)
        }
    }

    private fun notifyError(message: String) {
        mainHandler.post {
            listener?.onError(message)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description = "Controls audiobook text-to-speech playback"

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        try {
            val notification = buildNotification()

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: Exception) {
            // If notification fails, keep playback in foreground activity only.
        }
    }

    private fun updateNotification() {
        if (state != State.PLAYING && state != State.PAUSED) return

        try {
            val notification = buildNotification()
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Ignore notification update failures
        }
    }

    private fun buildNotification(): Notification {
        val isPlaying = state == State.PLAYING

        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val playPauseIntent = Intent(this, AudiobookTtsService::class.java)
            .setAction(ACTION_PLAY_PAUSE)

        val playPausePendingIntent = PendingIntent.getService(
            this,
            1,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudiobookTtsService::class.java)
            .setAction(ACTION_STOP)

        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(bookTitle ?: "Audiobook")
            .setContentText(if (isPlaying) "Playing" else "Paused")
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setSilent(true)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)

        val currentBookId = bookId

        if (currentBookId != null) {
            val contentIntent = Intent(this, AudiobookPlayerActivity::class.java)
                .putExtra("BOOK_ID", currentBookId)
                .putExtra("BOOK_TITLE", bookTitle)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

            val contentPendingIntent = PendingIntent.getActivity(
                this,
                3,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.setContentIntent(contentPendingIntent)
        }

        return builder.build()
    }

    private fun requestAudioFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { focusChange ->
                        handleAudioFocusChange(focusChange)
                    }
                    .build()

                audioFocusRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    { focusChange ->
                        handleAudioFocusChange(focusChange)
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (e: Exception) {
            // Audio focus is optional for minimal implementation.
        }
    }

    private fun abandonAudioFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (state == State.PLAYING) {
                    pause()
                }
            }
        }
    }
}