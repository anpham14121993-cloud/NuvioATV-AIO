package com.nuvio.tv.core.player.tts

import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

data class QueuedTtsAudio(
    val file: File,
    val speechRate: Float = 1.0f,
    val isTimeStretched: Boolean = false,
    val wavDurationMs: Long = -1L,
    val targetDurationMs: Long = -1L,
    val text: String = "",
    val ratePercent: Int = 100,
)

/**
 * Non-blocking audio player queue for TTS narration playback.
 *
 * Uses a dedicated audio-only Media3 [ExoPlayer] (Software PCM Sonic time-stretcher +
 * MediaCodec audio decoder) on a private [HandlerThread], automatically falling back to
 * Android [MediaPlayer] (and reporting unrecoverable errors upward for System TTS fallback)
 * so OEM Android TV HAL `MediaPlayer.setPlaybackParams` stalls never silence narration.
 */
@OptIn(UnstableApi::class)
class TtsAudioPlayerQueue(
    private val context: Context,
    private val onSpeechStart: () -> Unit = {},
    private val onSpeechEnd: () -> Unit = {},
    private val onPlaybackError: (item: QueuedTtsAudio, reason: String) -> Unit = { _, _ -> },
) {
    companion object {
        private const val TAG = "TtsAudioPlayerQueue"
        private const val WATCHDOG_TIMEOUT_MS = 18_000L
        private const val MAX_QUEUED_ITEMS = 8
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playerThread = HandlerThread("nuvio-tts-player").apply { start() }
    private val playerHandler = Handler(playerThread.looper)
    private val queue = ConcurrentLinkedQueue<QueuedTtsAudio>()

    // Accessed only on playerHandler.
    private var exoPlayer: ExoPlayer? = null
    private var exoBuildFailed = false
    private var fallbackMediaPlayer: MediaPlayer? = null
    private var activeEnhancer: LoudnessEnhancer? = null
    private var reportedSpeaking = false
    private var loggedFirstPlayback = false
    private var activeToken = 0L
    private var activeFile: File? = null
    private var activeItem: QueuedTtsAudio? = null
    private var activeInitialSpeed = 1.0f
    private var exoReadyHandledToken = -1L

    private val watchdogRunnable = Runnable {
        if (released) return@Runnable
        val item = activeItem ?: return@Runnable
        val token = activeToken
        NarrationDiag.log(appContext, "queue: playback timeout (${item.file.name})")
        Log.w(TAG, "Watchdog timeout for ${item.file.name}")
        notifyItemError(item, "TTS playback timeout")
        finishActiveItem(token, item.file)
    }

    @Volatile
    private var released = false

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

    private val exoListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val token = activeToken
            val item = activeItem ?: return
            if (released || fallbackMediaPlayer != null) return

            when (playbackState) {
                Player.STATE_READY -> {
                    if (exoReadyHandledToken == token) return
                    exoReadyHandledToken = token
                    val player = exoPlayer ?: return
                    val playerDuration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: -1L
                    val effectiveDuration = if (item.wavDurationMs > 0L) item.wavDurationMs else playerDuration
                    val refinedSpeed = computeSpeed(item, effectiveDuration)
                    currentSpeed = refinedSpeed
                    if (abs(refinedSpeed - activeInitialSpeed) > 0.02f) {
                        activeInitialSpeed = refinedSpeed
                        runCatching { player.playbackParameters = PlaybackParameters(refinedSpeed) }
                    }
                    applyGainEnhancer(player.audioSessionId)
                    rescheduleWatchdog(effectiveDuration, refinedSpeed)
                    if (!loggedFirstPlayback) {
                        loggedFirstPlayback = true
                        NarrationDiag.log(
                            appContext,
                            "queue: exo ready dur=${effectiveDuration}ms spd=${String.format(Locale.US, "%.2f", refinedSpeed)}x (${item.file.length()}B)"
                        )
                    }
                }

                Player.STATE_ENDED -> {
                    finishActiveItem(token, item.file)
                }

                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val token = activeToken
            val item = activeItem ?: return
            if (released || fallbackMediaPlayer != null) return
            val reason = "exo ${error.errorCodeName}: ${error.message ?: ""}".trim()
            NarrationDiag.log(appContext, "queue: $reason -> mp fallback")
            Log.w(TAG, "ExoPlayer TTS error ($reason); falling back to MediaPlayer")
            runCatching {
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
            }
            if (!item.file.exists() || item.file.length() <= 44L) {
                notifyItemError(item, reason)
                finishActiveItem(token, item.file)
            } else {
                playWithMediaPlayerFallback(item, token)
            }
        }
    }

    fun enqueue(audio: QueuedTtsAudio) {
        if (released || !audio.file.exists() || audio.file.length() <= 44L) {
            runCatching { audio.file.delete() }
            return
        }
        while (queue.size >= MAX_QUEUED_ITEMS) {
            val dropped = queue.poll() ?: break
            runCatching { dropped.file.delete() }
        }
        queue.add(audio)
        playerHandler.post {
            if (!released && !isPlaying) {
                playNext()
            }
        }
    }

    private fun ensureExoPlayer(): ExoPlayer? {
        if (exoPlayer != null) return exoPlayer
        if (exoBuildFailed || released) return null
        return try {
            val audioOnly = RenderersFactory { handler, _, audioListener, _, _ ->
                arrayOf(MediaCodecAudioRenderer(appContext, MediaCodecSelector.DEFAULT, handler, audioListener))
            }
            ExoPlayer.Builder(appContext, audioOnly)
                .setLooper(playerThread.looper)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    false
                )
                .build()
                .also { player ->
                    player.addListener(exoListener)
                    exoPlayer = player
                }
        } catch (t: Throwable) {
            exoBuildFailed = true
            NarrationDiag.log(appContext, "queue: exo init failed ${t.javaClass.simpleName}")
            Log.w(TAG, "Could not build audio-only ExoPlayer: ${t.message}", t)
            null
        }
    }

    private fun computeSpeed(item: QueuedTtsAudio, effectiveDurationMs: Long): Float {
        val maxSpeed = if (effectiveDurationMs >= 3000L) 1.45f else 1.50f
        var calcSpeed = item.speechRate.coerceIn(0.95f, maxSpeed)
        if (effectiveDurationMs > 0L && item.targetDurationMs > 0L) {
            val target = if (effectiveDurationMs >= 3000L) item.targetDurationMs + 220L else item.targetDurationMs
            val ratio = effectiveDurationMs.toFloat() / target.toFloat()
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
        return calcSpeed.coerceIn(0.95f, maxSpeed)
    }

    private fun applyGainEnhancer(audioSessionId: Int) {
        safeReleaseEnhancer()
        val vol = volumeMultiplier.coerceIn(0f, 1.5f)
        if (vol <= 1.02f || audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) {
            return
        }
        try {
            val enhancer = LoudnessEnhancer(audioSessionId)
            enhancer.setTargetGain(((vol - 1.0f) * 2800f).toInt())
            enhancer.enabled = true
            activeEnhancer = enhancer
        } catch (t: Throwable) {
            Log.w(TAG, "Could not enable LoudnessEnhancer: ${t.message}")
        }
    }

    private fun rescheduleWatchdog(effectiveDurationMs: Long, speed: Float) {
        playerHandler.removeCallbacks(watchdogRunnable)
        val timeoutMs = if (effectiveDurationMs > 0L) {
            ((effectiveDurationMs / speed.coerceAtLeast(0.5f)).toLong() + 8_000L).coerceIn(10_000L, 45_000L)
        } else {
            WATCHDOG_TIMEOUT_MS
        }
        playerHandler.postDelayed(watchdogRunnable, timeoutMs)
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

    private fun notifyItemError(item: QueuedTtsAudio, reason: String) {
        mainHandler.post {
            if (!released) {
                onPlaybackError(item, reason)
            }
        }
    }

    private fun clearQueueFiles() {
        while (true) {
            val item = queue.poll() ?: break
            runCatching { item.file.delete() }
        }
    }

    private fun safeReleaseFallbackMediaPlayer() {
        val player = fallbackMediaPlayer ?: return
        fallbackMediaPlayer = null
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

    private fun finishActiveItem(token: Long, file: File) {
        if (token != activeToken) {
            runCatching { file.delete() }
            return
        }
        playerHandler.removeCallbacks(watchdogRunnable)
        safeReleaseEnhancer()
        safeReleaseFallbackMediaPlayer()
        if (activeFile == file) {
            activeFile = null
        }
        activeItem = null
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

        val token = ++activeToken
        activeItem = item
        activeFile = file
        isPlaying = true
        notifySpeechStartIfNeeded()

        safeReleaseEnhancer()
        safeReleaseFallbackMediaPlayer()
        rescheduleWatchdog(item.wavDurationMs, item.speechRate)

        val exo = ensureExoPlayer()
        if (exo != null) {
            try {
                val initialSpeed = computeSpeed(item, item.wavDurationMs)
                activeInitialSpeed = initialSpeed
                currentSpeed = initialSpeed
                val baseVol = volumeMultiplier.coerceIn(0f, 1.0f)
                exo.volume = baseVol
                exo.playbackParameters = PlaybackParameters(initialSpeed)
                exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                exo.prepare()
                exo.play()
                return
            } catch (t: Throwable) {
                NarrationDiag.log(appContext, "queue: exo play threw ${t.javaClass.simpleName} -> mp")
                Log.w(TAG, "ExoPlayer playNext failed: ${t.message}", t)
                runCatching {
                    exo.stop()
                    exo.clearMediaItems()
                }
            }
        }

        playWithMediaPlayerFallback(item, token)
    }

    private fun playWithMediaPlayerFallback(item: QueuedTtsAudio, token: Long) {
        if (released || token != activeToken) return
        val file = item.file
        val player = try {
            MediaPlayer()
        } catch (t: Throwable) {
            NarrationDiag.log(appContext, "queue: mp create failed ${t.javaClass.simpleName}")
            notifyItemError(item, "MediaPlayer create failed: ${t.message}")
            finishActiveItem(token, file)
            return
        }
        fallbackMediaPlayer = player

        try {
            player.setAudioAttributes(
                AndroidAudioAttributes.Builder()
                    .setContentType(AndroidAudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                finishActiveItem(token, file)
            }
            player.setOnErrorListener { _, what, extra ->
                val reason = "MediaPlayer err what=$what extra=$extra"
                NarrationDiag.log(appContext, "queue: $reason (${file.name})")
                Log.w(TAG, "$reason for ${file.name}")
                notifyItemError(item, reason)
                finishActiveItem(token, file)
                true
            }
            player.setOnPreparedListener { mp ->
                if (released || token != activeToken || fallbackMediaPlayer !== mp) {
                    runCatching {
                        mp.setOnCompletionListener(null)
                        mp.setOnErrorListener(null)
                        mp.setOnPreparedListener(null)
                        mp.reset()
                        mp.release()
                    }
                    return@setOnPreparedListener
                }
                try {
                    val baseVol = volumeMultiplier.coerceIn(0f, 1.0f)
                    mp.setVolume(baseVol, baseVol)
                    // Intentionally keep fallback MediaPlayer at native 1.0x without playbackParams:
                    // calling setPlaybackParams on 24kHz mono MP3 stalls/errors on several Android TV HALs.
                    currentSpeed = 1.0f
                    mp.start()
                    if (!loggedFirstPlayback) {
                        loggedFirstPlayback = true
                        NarrationDiag.log(appContext, "queue: mp fallback playing (${file.length()}B)")
                    }
                } catch (t: Throwable) {
                    NarrationDiag.log(appContext, "queue: mp start failed ${t.javaClass.simpleName}")
                    notifyItemError(item, "MediaPlayer start failed: ${t.message}")
                    finishActiveItem(token, file)
                }
            }
            player.prepareAsync()
        } catch (t: Throwable) {
            NarrationDiag.log(appContext, "queue: mp setup failed ${t.javaClass.simpleName}")
            notifyItemError(item, "MediaPlayer setup failed: ${t.message}")
            finishActiveItem(token, file)
        }
    }

    private fun resetPlaybackState(releaseExo: Boolean) {
        activeToken++
        playerHandler.removeCallbacks(watchdogRunnable)
        safeReleaseEnhancer()
        safeReleaseFallbackMediaPlayer()
        runCatching {
            exoPlayer?.let { player ->
                if (releaseExo) {
                    player.removeListener(exoListener)
                    player.stop()
                    player.release()
                } else {
                    player.stop()
                    player.clearMediaItems()
                }
            }
        }
        if (releaseExo) {
            exoPlayer = null
        }
        activeItem = null
        activeFile?.let { f ->
            activeFile = null
            runCatching { f.delete() }
        }
        clearQueueFiles()
        isPlaying = false
        currentSpeed = 1.0f
        notifySpeechEndIfNeeded()
    }

    fun stop() {
        clearQueueFiles()
        playerHandler.removeCallbacksAndMessages(null)
        playerHandler.post {
            resetPlaybackState(releaseExo = false)
        }
    }

    fun release() {
        released = true
        clearQueueFiles()
        playerHandler.removeCallbacksAndMessages(null)
        playerHandler.post {
            resetPlaybackState(releaseExo = true)
            runCatching { playerThread.quitSafely() }
        }
    }
}
