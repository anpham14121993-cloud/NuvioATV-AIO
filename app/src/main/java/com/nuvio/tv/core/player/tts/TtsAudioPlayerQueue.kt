package com.nuvio.tv.core.player.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentLinkedQueue

data class QueuedTtsAudio(
    val file: File,
    val speechRate: Float = 1.0f,
    val isTimeStretched: Boolean = false,
    val wavDurationMs: Long = -1L,
    val targetDurationMs: Long = -1L
)

/**
 * Non-blocking audio player queue for TTS narration playback.
 *
 * Runs all MediaPlayer operations sequentially on a dedicated [HandlerThread]
 * so slow TV box audio HAL binder calls never block the UI thread and async
 * prepare/completion callbacks cannot race with stop/release.
 */
class TtsAudioPlayerQueue(
    private val context: Context,
    private val onSpeechStart: () -> Unit = {},
    private val onSpeechEnd: () -> Unit = {},
) {
    companion object {
        private const val TAG = "TtsAudioPlayerQueue"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val playerThread = HandlerThread("nuvio-tts-player").apply { start() }
    private val playerHandler = Handler(playerThread.looper)
    private val queue = ConcurrentLinkedQueue<QueuedTtsAudio>()

    @Volatile
    private var activePlayer: MediaPlayer? = null

    @Volatile
    private var activeEnhancer: LoudnessEnhancer? = null

    @Volatile
    private var released = false

    // Accessed only on playerHandler.
    private var reportedSpeaking = false

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var volumeMultiplier: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1.5f)
        }

    @Volatile
    var currentSpeed: Float = 1.0f
        private set

    fun enqueue(audio: QueuedTtsAudio) {
        if (released || !audio.file.exists() || audio.file.length() <= 44L) {
            runCatching { audio.file.delete() }
            return
        }
        queue.add(audio)
        playerHandler.post {
            if (!released && !isPlaying) {
                playNext()
            }
        }
    }

    private fun notifySpeechStartIfNeeded() {
        if (!reportedSpeaking && !released) {
            reportedSpeaking = true
            mainHandler.post {
                if (!released) onSpeechStart()
            }
        }
    }

    private fun notifySpeechEndIfNeeded() {
        if (reportedSpeaking) {
            reportedSpeaking = false
            mainHandler.post { onSpeechEnd() }
        }
    }

    private fun clearQueueFiles() {
        while (true) {
            val item = queue.poll() ?: break
            runCatching { item.file.delete() }
        }
    }

    private fun safeReleasePlayer(player: MediaPlayer?) {
        if (player == null) return
        runCatching { player.setOnCompletionListener(null) }
        runCatching { player.setOnErrorListener(null) }
        runCatching { player.setOnPreparedListener(null) }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    private fun safeReleaseEnhancer() {
        try {
            activeEnhancer?.enabled = false
            activeEnhancer?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing LoudnessEnhancer: ${t.message}")
        } finally {
            activeEnhancer = null
        }
    }

    private fun finishActiveItem(mp: MediaPlayer?, file: File) {
        safeReleaseEnhancer()
        if (mp != null && activePlayer === mp) {
            activePlayer = null
        }
        safeReleasePlayer(mp)
        runCatching { file.delete() }
        isPlaying = false
        if (!released) {
            playerHandler.post { playNext() }
        } else {
            notifySpeechEndIfNeeded()
        }
    }

    private fun playNext() {
        if (released) {
            clearQueueFiles()
            isPlaying = false
            currentSpeed = 1.0f
            notifySpeechEndIfNeeded()
            return
        }

        val item = queue.poll()
        if (item == null) {
            isPlaying = false
            currentSpeed = 1.0f
            notifySpeechEndIfNeeded()
            return
        }

        val file = item.file
        if (!file.exists() || file.length() <= 44L) {
            runCatching { file.delete() }
            playNext()
            return
        }

        isPlaying = true
        notifySpeechStartIfNeeded()

        safeReleaseEnhancer()
        val prevPlayer = activePlayer
        activePlayer = null
        safeReleasePlayer(prevPlayer)

        val player = try {
            MediaPlayer()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create MediaPlayer: ${t.message}", t)
            runCatching { file.delete() }
            isPlaying = false
            playerHandler.post { playNext() }
            return
        }
        activePlayer = player

        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )

            // Use file path directly: passing a FileInputStream FD closed before
            // prepareAsync() causes decode failures on Android TV boxes.
            player.setDataSource(file.absolutePath)

            player.setOnCompletionListener { mp ->
                runCatching { finishActiveItem(mp, file) }
            }

            player.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "MediaPlayer error: what=$what extra=$extra for file=${file.name}")
                runCatching { finishActiveItem(mp, file) }
                true
            }

            player.setOnPreparedListener { mp ->
                if (released || activePlayer !== mp) {
                    safeReleasePlayer(mp)
                    runCatching { file.delete() }
                    return@setOnPreparedListener
                }
                try {
                    val vol = volumeMultiplier.coerceIn(0f, 1.5f)
                    val baseVol = vol.coerceAtMost(1.0f)
                    mp.setVolume(baseVol, baseVol)
                    mp.start()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val durationMs = runCatching { mp.duration.toLong() }.getOrDefault(-1L)
                            val effectiveDuration = if (item.wavDurationMs > 0) item.wavDurationMs else durationMs
                            val maxSpeed = if (effectiveDuration >= 3000L) 1.45f else 1.50f

                            var calcSpeed = item.speechRate.coerceIn(0.95f, maxSpeed)
                            if (effectiveDuration > 0 && item.targetDurationMs > 0) {
                                val target = if (effectiveDuration >= 3000L) item.targetDurationMs + 220L else item.targetDurationMs
                                val ratio = effectiveDuration.toFloat() / target.toFloat()
                                if (ratio > calcSpeed) {
                                    calcSpeed = ratio
                                }
                            }

                            val backlogSize = queue.size
                            if (backlogSize >= 2) {
                                calcSpeed = maxOf((((backlogSize - 1) * 0.1f) + 1.0f) * calcSpeed, 1.25f)
                            } else if (backlogSize == 1) {
                                calcSpeed = (calcSpeed * 1.06f).coerceAtMost(maxSpeed)
                            }

                            currentSpeed = calcSpeed.coerceIn(0.95f, maxSpeed)
                            if (kotlin.math.abs(currentSpeed - 1.0f) > 0.02f) {
                                mp.playbackParams = mp.playbackParams.setSpeed(currentSpeed)
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Could not set playbackParams speed: ${t.message}")
                        }
                    }

                    if (vol > 1.0f) {
                        try {
                            val enhancer = LoudnessEnhancer(mp.audioSessionId)
                            enhancer.setTargetGain(((vol - 1.0f) * 2800f).toInt())
                            enhancer.enabled = true
                            activeEnhancer = enhancer
                        } catch (t: Throwable) {
                            Log.w(TAG, "Could not enable LoudnessEnhancer: ${t.message}")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to start MediaPlayer onPrepared for ${file.name}: ${t.message}", t)
                    finishActiveItem(mp, file)
                }
            }

            player.prepareAsync()
        } catch (t: Throwable) {
            if (t is FileNotFoundException) {
                Log.w(TAG, "File not found: ${file.name}")
            } else {
                Log.e(TAG, "Failed to start MediaPlayer for ${file.name}", t)
            }
            finishActiveItem(player, file)
        }
    }

    fun stop() {
        clearQueueFiles()
        playerHandler.removeCallbacksAndMessages(null)
        playerHandler.post {
            safeReleaseEnhancer()
            val mp = activePlayer
            activePlayer = null
            safeReleasePlayer(mp)
            clearQueueFiles()
            isPlaying = false
            currentSpeed = 1.0f
            notifySpeechEndIfNeeded()
        }
    }

    fun release() {
        released = true
        clearQueueFiles()
        playerHandler.removeCallbacksAndMessages(null)
        playerHandler.post {
            safeReleaseEnhancer()
            val mp = activePlayer
            activePlayer = null
            safeReleasePlayer(mp)
            clearQueueFiles()
            isPlaying = false
            currentSpeed = 1.0f
            notifySpeechEndIfNeeded()
            runCatching { playerThread.quitSafely() }
        }
    }
}
