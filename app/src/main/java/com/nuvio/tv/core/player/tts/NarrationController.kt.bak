package com.nuvio.tv.core.player.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Narrates the currently displayed subtitle line with the bundled offline
 * VieNeu engine and ducks the movie audio while speaking.
 *
 * The engine lives in [NarrationTtsService], a separate process: the ONNX
 * runtime and the G2P native library can abort a process on weak TV boxes,
 * and the player must survive that.
 *
 * The movie volume is changed through [ExoPlayer.setVolume] (not an audio
 * processor) so ducking also works when the audio is sent to the amplifier as
 * a bitstream (Dolby passthrough).
 */
@UnstableApi
class NarrationController(
    context: Context,
    private val playerProvider: () -> ExoPlayer?,
) {

    /** Load progress of the offline model, so the UI can explain a silent narration. */
    val status = MutableStateFlow(NarrationEngineStatus.LOADING)

    /** The last subtitle line the narration received, for the in-player diagnostics. */
    val lastCue = MutableStateFlow<String?>(null)

    /** The last failure of the engine or of a synthesis, for the diagnostics. */
    val lastError = MutableStateFlow<String?>(null)

    /** The last step the engine reported, so a stuck load can be pinpointed. */
    val stage = MutableStateFlow<String?>(null)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var enabled = false

    @Volatile
    private var narrationVolume = 0.9f

    @Volatile
    private var duckLevelPercent = 50

    @Volatile
    private var voice = DEFAULT_VOICE

    @Volatile
    private var released = false

    private val speaking = AtomicBoolean(false)
    private var lastSpokenText: String? = null
    private var lastCueAtMs = 0L
    private var cueIntervalEmaMs = 0f
    private var fadeGeneration = 0

    // ── service connection ────────────────────────────────────────────────────

    @Volatile
    private var serviceMessenger: Messenger? = null

    @Volatile
    private var bound = false

    /** A line that arrived before the service was connected. */
    @Volatile
    private var pendingLine: Pair<String, Int>? = null

    /** The service died this often; a repeated death must not become a crash loop. */
    @Volatile
    private var serviceFailures = 0

    /** Set when the engine process keeps dying, so the narration stops retrying. */
    @Volatile
    private var serviceDisabled = false

    private val clientMessenger = Messenger(object : Handler(mainHandler.looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                NarrationTtsService.MSG_READY -> {
                    serviceMessenger?.let { messenger ->
                        messenger.send(Message.obtain(null, NarrationTtsService.MSG_WARMUP))
                        val volume = Message.obtain(null, NarrationTtsService.MSG_SET_VOLUME)
                        volume.data = Bundle().apply {
                            putFloat(NarrationTtsService.ARG_VOLUME, narrationVolume)
                        }
                        messenger.send(volume)
                    }
                    pendingLine?.let { (text, rate) ->
                        pendingLine = null
                        sendSpeak(text, rate, null)
                    }
                }
                NarrationTtsService.MSG_STATUS -> {
                    val state = msg.data?.getString(NarrationTtsService.ARG_STATE)
                    status.value = runCatching { NarrationEngineStatus.valueOf(state.orEmpty()) }
                        .getOrDefault(NarrationEngineStatus.LOADING)
                }
                NarrationTtsService.MSG_SPEECH_START -> {
                    speaking.set(true)
                    duckPlayer()
                }
                NarrationTtsService.MSG_SPEECH_END -> {
                    speaking.set(false)
                    unduckPlayer()
                }
                NarrationTtsService.MSG_STAGE -> {
                    stage.value = msg.data?.getString(NarrationTtsService.ARG_STAGE)
                }
                NarrationTtsService.MSG_ERROR -> {
                    val detail = msg.data?.getString(NarrationTtsService.ARG_DETAIL).orEmpty()
                    Log.w(TAG, "narration error: $detail")
                    lastError.value = detail
                }
                NarrationTtsService.MSG_RELEASED -> {
                    serviceMessenger = null
                }
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bound = true
            serviceDisabled = false
            stage.value = "bind: connected"
            val messenger = Messenger(binder)
            serviceMessenger = messenger
            val hello = Message.obtain(null, NarrationTtsService.MSG_HELLO)
            hello.replyTo = clientMessenger
            runCatching { messenger.send(hello) }.onFailure {
                Log.w(TAG, "hello send failed", it)
                lastError.value = "service: hello ${it.javaClass.simpleName}"
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            bound = false
            serviceMessenger = null
            lastError.value = "service: null binding"
            stage.value = "bind: null"
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            serviceMessenger = null
            serviceFailures++
            status.value = NarrationEngineStatus.FAILED
            lastError.value = "service: died ${serviceFailures}x"
            stage.value = "bind: died"
            if (serviceFailures >= 2) {
                serviceDisabled = true
                Log.w(TAG, "narration service died ${serviceFailures} times, giving up")
            }
            serviceMessenger = null
            serviceFailures++
            status.value = NarrationEngineStatus.FAILED
            lastError.value = "service: died ${serviceFailures}x"
            if (serviceFailures >= 2) {
                // The engine process keeps crashing on this device: stop the loop.
                serviceDisabled = true
                Log.w(TAG, "narration service died ${serviceFailures} times, giving up")
            }
        }
    }

    // ── configuration ────────────────────────────────────────────────────────

    fun setEnabled(value: Boolean) {
        if (enabled != value) Log.i(TAG, "narration enabled=$value")
        enabled = value
        if (!value) {
            stopSpeaking()
            unduckPlayer()
        } else {
            // Load the model right away: the first line of a film would otherwise
            // be lost while the 261 MB of weights are extracted and loaded.
            warmUp()
        }
    }

    /** Starts loading the offline model without waiting for the first cue. */
    fun warmUp() {
        if (released) return
        connect()
        val messenger = serviceMessenger
        if (messenger == null) {
            // The binding is asynchronous: the hello/warmup handshake finishes in
            // onServiceConnected. Report it if the service never answers.
            mainHandler.postDelayed({
                if (!released && status.value == NarrationEngineStatus.LOADING && stage.value == null) {
                    lastError.value = "service: no reply (bound=$bound)"
                }
            }, 8000L)
            return
        }
        runCatching { messenger.send(Message.obtain(null, NarrationTtsService.MSG_WARMUP)) }
        mainHandler.postDelayed({
            if (!released && status.value == NarrationEngineStatus.LOADING) {
                stage.value = "load: still loading"
                lastError.value = "load: still loading (${stage.value ?: "no step"})"
            }
        }, 120_000L)
    }

    /** Asks the service how far the load got, for the diagnostics while a dialog is open. */
    fun probe() {
        connect()
        val messenger = serviceMessenger
        if (messenger == null) {
            stage.value = "bind: waiting"
            if (!bound && !serviceDisabled) lastError.value = "service: not bound"
            return
        }
        runCatching { messenger.send(Message.obtain(null, NarrationTtsService.MSG_PING)) }
    }

    /** Speaks one fixed sentence to test the whole pipeline end to end. */
    fun testSpeak() {
        if (!enabled || released) return
        val messenger = serviceMessenger
        if (messenger == null) {
            connect()
            lastError.value = "service: not bound"
            return
        }
        lastError.value = null
        runCatching { messenger.send(Message.obtain(null, NarrationTtsService.MSG_TEST)) }
    }

    fun setVolumePercent(volumePercent: Int) {
        narrationVolume = (volumePercent.coerceIn(0, 100)) / 100f
        val message = Message.obtain(null, NarrationTtsService.MSG_SET_VOLUME)
        message.data = Bundle().apply { putFloat(NarrationTtsService.ARG_VOLUME, narrationVolume) }
        runCatching { serviceMessenger?.send(message) }
    }

    fun setDuckLevelPercent(duckPercent: Int) {
        duckLevelPercent = duckPercent.coerceIn(0, 100)
    }

    fun setVoice(voiceName: String) {
        voice = voiceName
    }

    // ── narration ────────────────────────────────────────────────────────────

    /**
     * Called with the text of the subtitle line that is currently on screen.
     *
     * @param windowSec how long the line stays visible, used to speed the
     *   narration up so it fits the window.
     */
    fun onCueText(rawText: String?, windowSec: Float? = null) {
        if (!enabled || released) return
        Log.d(TAG, "cue: ${rawText?.take(60)}")
        val text = cleanText(rawText) ?: return
        lastCue.value = text
        if (text == lastSpokenText) return
        if (!shouldNarrateText(text)) return

        val now = SystemClock.uptimeMillis()
        val previousCueAt = lastCueAtMs
        lastCueAtMs = now
        if (previousCueAt > 0L) {
            val deltaMs = (now - previousCueAt).toFloat()
            if (deltaMs in 400f..15000f) {
                cueIntervalEmaMs = if (cueIntervalEmaMs <= 0f) {
                    deltaMs
                } else {
                    cueIntervalEmaMs + (deltaMs - cueIntervalEmaMs) * CUE_INTERVAL_EMA_ALPHA
                }
            }
        }

        lastSpokenText = text
        val effectiveWindow = windowSec ?: cueIntervalEmaMs
            .takeIf { it > 0f }
            ?.let { it / 1000f }
        val budgetMs = effectiveWindow?.let { sec ->
            ((sec * 1000f).toLong() - NEXT_CUE_GUARD_MS).coerceAtLeast(150L)
        }
        val rate = chooseRatePercent(text.length, effectiveWindow)
        sendSpeak(text, rate, budgetMs)
    }

    /** Pre-synthesizes upcoming lines so playback starts instantly. */
    fun prefetch(text: String) {
        if (!enabled || released) return
        val clean = cleanText(text) ?: return
        val message = Message.obtain(null, NarrationTtsService.MSG_PREFETCH)
        message.data = Bundle().apply {
            putString(NarrationTtsService.ARG_TEXT, clean)
            putString(NarrationTtsService.ARG_VOICE, voice)
        }
        runCatching { serviceMessenger?.send(message) }
    }

    private fun sendSpeak(text: String, rate: Int, budgetMs: Long?) {
        val messenger = serviceMessenger
        if (messenger == null) {
            if (serviceDisabled) return
            connect()
            pendingLine = text to rate
            return
        }
        val message = Message.obtain(null, NarrationTtsService.MSG_SPEAK)
        message.data = Bundle().apply {
            putString(NarrationTtsService.ARG_TEXT, text)
            putString(NarrationTtsService.ARG_VOICE, voice)
            putInt(NarrationTtsService.ARG_RATE, rate)
            putLong(NarrationTtsService.ARG_BUDGET, budgetMs ?: 0L)
        }
        runCatching { messenger.send(message) }
    }

    private fun connect() {
        if (released || serviceDisabled) return
        if (bound) return
        stage.value = "bind: calling bindService"
        val intent = Intent(appContext, NarrationTtsService::class.java)
        val started = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.w(TAG, "could not bind the narration service", t)
            lastError.value = "service: ${t.javaClass.simpleName}"
            false
        }
        if (!started) {
            Log.w(TAG, "bindService returned false")
            stage.value = "bind: returned false"
            lastError.value = "service: bind returned false"
        } else {
            stage.value = "bind: requested"
            lastError.value = null
        }
    }

    fun stopSpeaking() {
        lastSpokenText = null
        runCatching { serviceMessenger?.send(Message.obtain(null, NarrationTtsService.MSG_STOP)) }
        speaking.set(false)
        unduckPlayer()
    }

    fun release() {
        released = true
        enabled = false
        runCatching { serviceMessenger?.send(Message.obtain(null, NarrationTtsService.MSG_RELEASE)) }
        runCatching { appContext.unbindService(connection) }
        serviceMessenger = null
        bound = false
        speaking.set(false)
        unduckPlayer()
    }

    // ── ducking ──────────────────────────────────────────────────────────────

    private fun duckPlayer() {
        val target = (duckLevelPercent / 100f * 0.25f).coerceAtLeast(0.02f)
        fadePlayerVolumeTo(target)
    }

    private fun unduckPlayer() {
        if (speaking.get()) return
        fadePlayerVolumeTo(1f)
    }

    private fun fadePlayerVolumeTo(target: Float) {
        val generation = ++fadeGeneration
        mainHandler.post {
            val player = playerProvider() ?: return@post
            val start = player.volume
            if (kotlin.math.abs(start - target) < 0.01f) {
                runCatching { player.volume = target }
                return@post
            }
            fun step(index: Int) {
                if (generation != fadeGeneration) return
                val t = (index + 1).toFloat() / FADE_STEPS
                val value = start + (target - start) * t
                runCatching { player.volume = value.coerceIn(0f, 1f) }
                if (index < FADE_STEPS) {
                    mainHandler.postDelayed({ step(index + 1) }, FADE_STEP_MS)
                }
            }
            step(0)
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Picks a rate bump so the line fits its display window.
     * Returns 0..[MAX_RATE_BUMP_PERCENT].
     */
    private fun chooseRatePercent(charCount: Int, windowSec: Float?): Int {
        val fit = if (windowSec == null || windowSec <= 0f) {
            0
        } else {
            val window = windowSec.coerceAtLeast(MIN_SPEECH_WINDOW_SEC)
            val estimated = charCount / VI_CHARS_PER_SECOND
            if (estimated <= window) 0 else (((estimated / window) - 1f) * 100f).toInt()
        }
        return (BASE_RATE_PERCENT + fit).coerceIn(0, MAX_RATE_BUMP_PERCENT) / RATE_STEP * RATE_STEP
    }

    /** Strips markup and collapses whitespace; null when nothing is left. */
    private fun cleanText(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        var text = rawText.replace(Regex("<[^>]*>"), " ")
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        text = text.replace(Regex("\\s+"), " ").trim()
        return text.takeIf { it.isNotBlank() }
    }

    /** Only narrate Vietnamese (or other non-Latin) text. */
    private fun shouldNarrateText(text: String): Boolean {
        if (looksVietnamese(text)) return true
        if (hasNonLatinScript(text)) return true
        val letters = text.count { it.isLetter() }
        return letters in 1..39 && text.all { !it.isLetter() || it in 'A'..'Z' || it in 'a'..'z' }
    }

    private fun looksVietnamese(text: String): Boolean {
        val marks = text.count { it in VIETNAMESE_MARKS }
        if (marks == 0) return false
        return text.count { it.isLetter() } in 1..160
    }

    private fun hasNonLatinScript(text: String): Boolean {
        for (char in text) {
            val block = Character.UnicodeBlock.of(char) ?: continue
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                block == Character.UnicodeBlock.HANGUL_JAMO
            ) {
                return true
            }
        }
        return false
    }

    private companion object {
        const val TAG = "NuvioNarration"
        const val DEFAULT_VOICE = "Thùy Dung"
        const val VI_CHARS_PER_SECOND = 12f
        const val BASE_RATE_PERCENT = 10
        const val MAX_RATE_BUMP_PERCENT = 50
        const val RATE_STEP = 5
        const val MIN_SPEECH_WINDOW_SEC = 1.2f
        const val NEXT_CUE_GUARD_MS = 120L
        const val CUE_INTERVAL_EMA_ALPHA = 0.35f
        const val FADE_STEPS = 10
        const val FADE_STEP_MS = 30L
        val VIETNAMESE_MARKS = charArrayOf(
            '̀', '́', '̃', '̄', '̉', '̣',
            '̌', '̂', '̃', '̰', '̱', '̆', '̊', '̋', '̐',
            '̀', '́', '͂', '̓', 'ͅ', '͆', '͇', '̀',
        )
    }
}
