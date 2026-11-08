package com.nuvio.tv.core.player.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
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
 * Non-blocking audio player queue directly matching Vax k26 / TtsAudioPlayerQueue.
 *
 * Features:
 * - Plays queued audio files seamlessly with MediaPlayer on STREAM_MUSIC (USAGE_MEDIA).
 * - Dynamic catch-up playback rate:
 *     If queue size >= 2: increases MediaPlayer speed (up to 1.6x) so dialogue never lags.
 *     If queue size == 1: slight speed bump (1.08x).
 * - LoudnessEnhancer integration: boosts narration volume above movie explosions when volume > 1.0.
 * - Automatic file cleanup on completion.
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
    private val queue = ConcurrentLinkedQueue<QueuedTtsAudio>()

    private var activePlayer: MediaPlayer? = null
    private var activeEnhancer: LoudnessEnhancer? = null

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
        if (!audio.file.exists() || audio.file.length() <= 44L) {
            runCatching { audio.file.delete() }
            return
        }
        queue.add(audio)
        mainHandler.post {
            if (!isPlaying) {
                playNext()
            }
        }
    }

    private fun safeReleasePlayer(player: MediaPlayer?) {
        if (player == null) return
        try {
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            player.setOnPreparedListener(null)
            player.reset()
            player.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing MediaPlayer: ${t.message}")
        }
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

    private fun playNext() {
        if (queue.isEmpty()) {
            isPlaying = false
            currentSpeed = 1.0f
            onSpeechEnd()
            return
        }

        val item = queue.poll()
        if (item == null) {
            isPlaying = false
            currentSpeed = 1.0f
            onSpeechEnd()
            return
        }

        val file = item.file
        if (!file.exists() || file.length() <= 44L) {
            runCatching { file.delete() }
            playNext()
            return
        }

        isPlaying = true
        onSpeechStart()

        safeReleasePlayer(activePlayer)
        safeReleaseEnhancer()

        val player = MediaPlayer()
        activePlayer = player

        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )

            FileInputStream(file).use { fis ->
                player.setDataSource(fis.fd)
            }

            player.setOnCompletionListener { mp ->
                safeReleaseEnhancer()
                if (activePlayer == mp) {
                    activePlayer = null
                }
                safeReleasePlayer(mp)
                runCatching { file.delete() }
                mainHandler.post { playNext() }
            }

            player.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "MediaPlayer error: what=$what extra=$extra for file=${file.name}")
                safeReleaseEnhancer()
                if (activePlayer == mp) {
                    activePlayer = null
                }
                safeReleasePlayer(mp)
                runCatching { file.delete() }
                mainHandler.post { playNext() }
                true
            }

            player.setOnPreparedListener { mp ->
                val vol = volumeMultiplier.coerceIn(0f, 1.5f)
                val baseVol = vol.coerceAtMost(1.0f)
                mp.setVolume(baseVol, baseVol)
                mp.start()

                // Calculate dynamic playback speed matching Vax k26 logic
                try {
                    val durationMs = runCatching { mp.duration.toLong() }.getOrDefault(-1L)
                    val effectiveDuration = if (item.wavDurationMs > 0) item.wavDurationMs else durationMs
                    val maxSpeed = if (effectiveDuration >= 3000L) 1.5f else 1.6f

                    var calcSpeed = item.speechRate.coerceIn(0.95f, maxSpeed)
                    if (effectiveDuration > 0 && item.targetDurationMs > 0) {
                        val target = if (effectiveDuration >= 3000L) item.targetDurationMs + 220L else item.targetDurationMs
                        val ratio = effectiveDuration.toFloat() / target.toFloat()
                        if (ratio > calcSpeed) {
                            calcSpeed = ratio
                        }
                    }

                    // Vax backlog catch-up:
                    val backlogSize = queue.size
                    if (backlogSize >= 2) {
                        calcSpeed = Math.max((((backlogSize - 1) * 0.1f) + 1.0f) * calcSpeed, 1.3f)
                    } else if (backlogSize == 1) {
                        calcSpeed = (calcSpeed * 1.08f).coerceAtMost(maxSpeed)
                    }

                    currentSpeed = calcSpeed.coerceIn(0.95f, maxSpeed)
                    mp.playbackParams = mp.playbackParams.setSpeed(currentSpeed)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set playbackParams speed: ${e.message}")
                }

                // Vax LoudnessEnhancer: boost audio if volume > 1.0
                if (vol > 1.0f) {
                    try {
                        val enhancer = LoudnessEnhancer(mp.audioSessionId)
                        enhancer.setTargetGain(((vol - 1.0f) * 2800f).toInt())
                        enhancer.enabled = true
                        activeEnhancer = enhancer
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not enable LoudnessEnhancer: ${e.message}")
                    }
                }
            }

            player.prepareAsync()
        } catch (e: Exception) {
            if (e is FileNotFoundException) {
                Log.w(TAG, "File not found: ${file.name}")
            } else {
                Log.e(TAG, "Failed to start MediaPlayer for ${file.name}", e)
            }
            if (activePlayer == player) {
                activePlayer = null
            }
            safeReleasePlayer(player)
            safeReleaseEnhancer()
            runCatching { file.delete() }
            isPlaying = false
            mainHandler.post { playNext() }
        }
    }

    fun stop() {
        mainHandler.post {
            safeReleaseEnhancer()
            val mp = activePlayer
            activePlayer = null
            safeReleasePlayer(mp)
            while (true) {
                val item = queue.poll() ?: break
                runCatching { item.file.delete() }
            }
            isPlaying = false
            currentSpeed = 1.0f
            onSpeechEnd()
        }
    }

    fun release() {
        stop()
    }
}
