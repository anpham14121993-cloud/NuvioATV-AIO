package com.nuvio.tv.core.player.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Narrates the currently displayed subtitle line with the bundled offline
 * VieNeu engine and ducks the movie audio while speaking.
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

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-vieneu-tts").apply { isDaemon = true }
    }

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

    private val engineLock = Any()
    private var engine: NarrationTtsEngine? = null
    private val engineReady = AtomicBoolean(false)

    private val speaking = AtomicBoolean(false)
    private var lastSpokenText: String? = null
    private var lastCueAtMs = 0L
    private var cueIntervalEmaMs = 0f
    private var fadeGeneration = 0

    // ── configuration ────────────────────────────────────────────────────────

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            stopSpeaking()
            unduckPlayer()
        }
    }

    fun setVolumePercent(volumePercent: Int) {
        narrationVolume = (volumePercent.coerceIn(0, 100)) / 100f
        engine?.setNarrationVolume(narrationVolume)
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
        val text = cleanText(rawText) ?: return
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
        speak(text, rateWindowSec = effectiveWindow, budgetMs = budgetMs)
    }

    /** Pre-synthesizes upcoming lines so playback starts instantly. */
    fun prefetch(text: String) {
        if (!enabled || released) return
        val clean = cleanText(text) ?: return
        worker.execute {
            runCatching {
                val e = engineOrCreate() ?: return@runCatching
                e.prefetch(clean, voice, 0)
            }.onFailure { Log.w(TAG, "prefetch failed", it) }
        }
    }

    private fun speak(text: String, rateWindowSec: Float?, budgetMs: Long?) {
        val rate = chooseRatePercent(text.length, rateWindowSec)
        worker.execute {
            runCatching {
                val e = engineOrCreate() ?: return@runCatching
                e.setNarrationVolume(narrationVolume)
                e.speak(
                    text = text,
                    voiceApiName = voice,
                    ratePercent = rate,
                    cutOnNewer = true,
                    budgetMs = budgetMs,
                    onResult = { ok, detail ->
                        mainHandler.post {
                            if (ok) {
                                speaking.set(true)
                                duckPlayer()
                            } else {
                                Log.w(TAG, "narration failed: $detail")
                            }
                        }
                    },
                )
            }.onFailure { Log.e(TAG, "narration failed", it) }
        }
    }

    fun stopSpeaking() {
        lastSpokenText = null
        runCatching { engine?.stop() }
        speaking.set(false)
        unduckPlayer()
    }

    fun release() {
        released = true
        enabled = false
        val e = synchronized(engineLock) {
            val current = engine
            engine = null
            current
        }
        runCatching { e?.release() }
        runCatching { worker.shutdownNow() }
        engineReady.set(false)
        speaking.set(false)
        unduckPlayer()
    }

    // ── engine ───────────────────────────────────────────────────────────────

    private fun engineOrCreate(): NarrationTtsEngine? {
        engine?.let { return it }
        synchronized(engineLock) {
            engine?.let { return it }
            if (released) return null
            val created = runCatching {
                NarrationEngineFactory.create(
                    context = appContext,
                    onSpeechStart = { mainHandler.post { speaking.set(true); duckPlayer() } },
                    onSpeechEnd = { mainHandler.post { speaking.set(false); unduckPlayer() } },
                    onError = { text, rate -> Log.w(TAG, "narration error: $text") },
                )
            }.getOrElse { error ->
                Log.e(TAG, "could not create the narration engine", error)
                null
            }
            engine = created
            created?.setNarrationVolume(narrationVolume)
            runCatching { created?.warmUp() }
            engineReady.set(created != null)
            return created
        }
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
    private fun cleanText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw.replace(Regex("<[^>]*>"), " ")
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
                block == Character.UnicodeBlock.HANGUL_JAMO ||
                block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
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
            '̀', '́', '͂', '̓', 'ͅ', '͆', '͇', '̀',
        )
    }
}
