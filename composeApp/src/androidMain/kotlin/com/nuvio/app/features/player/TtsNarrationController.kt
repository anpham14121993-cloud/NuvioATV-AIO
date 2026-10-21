package com.nuvio.app.features.player

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android-only Vietnamese subtitle narrator ("thuyết minh TTS").
 *
 * When enabled and the active subtitle track is Vietnamese, each subtitle cue is
 * spoken aloud with the device TTS engine (vi-VN voice) while the player volume
 * is ducked, then restored when the utterance ends.
 *
 * No-ops on devices without a Vietnamese TTS voice installed (common on TV boxes);
 * callers can use [vietnameseVoiceStatus] to explain why narration stays silent.
 */
class TtsNarrationController(
    private val appContext: Context,
    private val player: ExoPlayer,
) {
    enum class VietnameseVoiceStatus {
        UNKNOWN,
        READY,
        NOT_AVAILABLE,
    }

    @Volatile
    var vietnameseVoiceStatus: VietnameseVoiceStatus = VietnameseVoiceStatus.UNKNOWN
        private set

    private var engine: NarrationTtsEngine? = null
    private var triedCreateEngine = false

    /** Engine voice short name, e.g. vi-VN-HoaiMyNeural. */
    @Volatile
    var narrationVoice: String = TtsNarrationVoice.Female.apiName

    fun setNarrationVoice(voice: TtsNarrationVoice) {
        narrationVoice = voice.apiName
    }

    private val ttsReady = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val utteranceSeq = AtomicInteger(0)

    // Diagnostic counters (per player session) to find where cues get dropped.
    private val ctrOnCues = AtomicInteger(0)
    private val ctrSidecar = AtomicInteger(0)
    private val ctrSpeak = AtomicInteger(0)
    private val ctrSkipBlank = AtomicInteger(0)
    private val ctrSkipLang = AtomicInteger(0)
    private val ctrSkipSniff = AtomicInteger(0)

    @Volatile
    private var enabled = false

    @Volatile
    var narrationVolume: Float = 1f
        private set

    /** Movie volume level while narrating (0..1). */
    @Volatile
    var duckLevel: Float = 0.5f
        private set

    private val fadeGeneration = AtomicInteger(0)

    fun setVolumePercent(volumePercent: Int) {
        narrationVolume = (volumePercent.coerceIn(0, 100) / 100f).coerceIn(0f, 1f)
        engine?.setNarrationVolume(narrationVolume)
    }

    fun setDuckLevelPercent(duckPercent: Int) {
        duckLevel = (duckPercent.coerceIn(5, 100) / 100f).coerceIn(0.05f, 1f)
    }

    @Volatile
    private var activeSubtitleIsVietnamese = false

    /**
     * Last line handed to [speakResolved]. media3 re-emits the same cue many
     * times per second; re-speaking it would stopTrack() (mute the audio) and
     * bump the engine generation, invalidating the in-flight synthesis so
     * nothing ever plays. Consecutive identical cues are de-duplicated.
     */
    @Volatile
    private var lastSpokenText: String? = null
    private var lastCueAtMs = 0L
    private var cueIntervalEmaMs = 0f

    private var engineWarmedUp = false

    private var tts: TextToSpeech? = null
    private var released = false

    // ExoPlayer setters must run on the app (main) thread, but TTS
    // callbacks arrive on binder/worker threads.
    private val mainHandler = Handler(appContext.mainLooper)

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            speaking.set(true)
            duckPlayer()
        }

        override fun onDone(utteranceId: String?) {
            speaking.set(false)
            unduckPlayer()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            speaking.set(false)
            unduckPlayer()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            speaking.set(false)
            unduckPlayer()
        }
    }

    /** Lazily creates the narration engine (shared by speak/prefetch/test/warm-up). */
    private fun engineOrCreate(): NarrationTtsEngine? {
        if (engine != null) return engine
        if (triedCreateEngine) return null
        triedCreateEngine = true
        return runCatching {
            NarrationEngineFactory.create(
                appContext,
                onSpeechStart = { notifySpeechStart() },
                onSpeechEnd = { notifySpeechEnd() },
                onError = { failedText, failedRate -> systemSpeak(failedText, failedRate) },
            )
        }.getOrNull().also { engine = it }
    }

    fun start() {
        if (tts != null) return
        try {
            tts = TextToSpeech(appContext) { status ->
                runCatching { onTtsInit(status) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "System TTS init failed", t)
            tts = null
            vietnameseVoiceStatus = VietnameseVoiceStatus.NOT_AVAILABLE
        }
        // Warm the engine (model load) in the background so the first cue is instant.
        runCatching { engineOrCreate()?.warmUp() }
    }

    private fun onTtsInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            vietnameseVoiceStatus = VietnameseVoiceStatus.NOT_AVAILABLE
            return
        }
        val engine = tts ?: return
        val availability = try {
            engine.setLanguage(VIETNAMESE)
        } catch (_: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (availability == TextToSpeech.LANG_MISSING_DATA ||
            availability == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            vietnameseVoiceStatus = VietnameseVoiceStatus.NOT_AVAILABLE
            return
        }
        try {
            engine.setOnUtteranceProgressListener(progressListener)
        } catch (t: Throwable) {
            Log.e(TAG, "setOnUtteranceProgressListener failed", t)
            vietnameseVoiceStatus = VietnameseVoiceStatus.NOT_AVAILABLE
            return
        }
        ttsReady.set(true)
        vietnameseVoiceStatus = VietnameseVoiceStatus.READY
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            stopSpeaking()
        } else {
            runCatching { engineOrCreate()?.warmUp() }
        }
    }

    /**
     * Pre-synthesizes upcoming lines so they play instantly when shown.
     * Never interrupts playback; failures are silent (speak path retries).
     */
    fun prefetchUpcoming(items: List<Pair<String, Float>>, forcedVietnamese: Boolean = false) {
        try {
            if (!enabled) return
            val engine = engineOrCreate() ?: return
            if (!engineWarmedUp) {
                runCatching { engine.warmUp() }
                engineWarmedUp = true
            }
            for ((text, windowSec) in items) {
                if (text.isBlank()) continue
                if (!forcedVietnamese && !shouldNarrateText(text)) continue
                engine.prefetch(text, narrationVoice, chooseRatePercent(text.length, windowSec))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "prefetchUpcoming failed", t)
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val manager =
                appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                ?: return
            manager.setPrimaryClip(android.content.ClipData.newPlainText("tts-diag", text))
        } catch (t: Throwable) {
            Log.e(TAG, "clipboard failed", t)
        }
    }

    private fun toast(message: String) {
        mainHandler.post {
            runCatching {
                android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun setActiveSubtitleIsVietnamese(value: Boolean) {
        if (activeSubtitleIsVietnamese == value) return
        activeSubtitleIsVietnamese = value
        if (!value) stopSpeaking()
    }

    /** Feeds current cues from [androidx.media3.common.Player.Listener.onCues]. */
    fun onCues(cues: List<Cue>) {
        try {
            onCuesInternal(cues)
        } catch (t: Throwable) {
            Log.e(TAG, "onCues failed", t)
        }
    }

    private fun onCuesInternal(cues: List<Cue>) {
        if (!enabled) return
        ctrOnCues.incrementAndGet()
        val text = combineCueTexts(cues)
        if (text.isNullOrBlank()) {
            ctrSkipBlank.incrementAndGet()
            return
        }
        // Trust an explicit non-Vietnamese track label; otherwise sniff the content
        // (covers missing/mislabeled track metadata).
        val trackLang = selectedTextTrackLanguage()
        if (trackLang != null && !isVietnameseLanguageCode(trackLang)) {
            ctrSkipLang.incrementAndGet()
            return
        }
        if (trackLang == null && !shouldNarrateText(text)) {
            ctrSkipSniff.incrementAndGet()
            return
        }
        // Embedded tracks carry no end time. Measure how often cues change and use
        // that cadence as the speech window so narration cannot drift a full line
        // behind the picture (which is what "reads line N while N+1 is shown" means).
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
        if (text == lastSpokenText) return
        lastSpokenText = text
        TtsTrace.log("cue: speak len=" + text.length)
        ctrSpeak.incrementAndGet()
        val windowSec = if (cueIntervalEmaMs > 0f) cueIntervalEmaMs / 1000f else null
        speakResolved(
            text,
            rateWindowSec = windowSec,
            budgetMs = windowSec?.let { sec ->
                ((sec * 1000f).toLong() - NEXT_CUE_GUARD_MS).coerceAtLeast(150L)
            },
            cutOnNewer = true,
        )
    }

    /**
     * Feeds cues rendered by the sidecar pipeline (external SRT/ASS files), which
     * never reach ExoPlayer's onCues.
     *
     * @param windowSec seconds left before this cue disappears (null = unknown).
     */
    fun onSidecarCues(
        cues: List<Cue>,
        budgetSec: Float? = null,
        rateWindowSec: Float? = null,
        forcedVietnamese: Boolean = false,
    ) {
        try {
            onSidecarCuesInternal(cues, budgetSec, rateWindowSec, forcedVietnamese)
        } catch (t: Throwable) {
            Log.e(TAG, "onSidecarCues failed", t)
        }
    }

    private fun onSidecarCuesInternal(
        cues: List<Cue>,
        budgetSec: Float?,
        rateWindowSec: Float?,
        forcedVietnamese: Boolean,
    ) {
        if (!enabled) return
        ctrSidecar.incrementAndGet()
        val text = combineCueTexts(cues)
        if (text.isNullOrBlank()) {
            ctrSkipBlank.incrementAndGet()
            return
        }
        if (!forcedVietnamese && !shouldNarrateText(text)) {
            ctrSkipSniff.incrementAndGet()
            return
        }
        if (text == lastSpokenText) return
        lastSpokenText = text
        ctrSpeak.incrementAndGet()
        // Rate fits the slot up to the NEXT cue's start (same value the prefetch
        // pass used -> cache hit). The budget lets playback spill past this cue's
        // end into the gap, but stops just before the next cue's start.
        speakResolved(
            text,
            rateWindowSec = rateWindowSec,
            budgetMs = budgetSec?.let { sec ->
                ((sec * 1000f).toLong() - NEXT_CUE_GUARD_MS).coerceAtLeast(150L)
            },
            cutOnNewer = false,
        )
    }

    /** Trusts Vietnamese hints in an external subtitle URL/file name. */
    fun looksLikeVietnameseUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase(Locale.US)
        return lower.contains("viet") || lower.contains("vnm") || lower.contains("viesub") ||
            lower.contains("phude") || lower.contains("vsub") ||
            lower.contains(".vi.") || lower.contains(".vi_") || lower.contains("-vi.") ||
            lower.contains("_vi.") || lower.contains("/vi/")
    }

    /**
     * Sidecar subtitles carry no language tag: narrate when the text looks
     * Vietnamese, contains CJK/Hangul (foreign names/terms inside a VI sub),
     * or is a short interjection (e.g. a lone foreign name like "John!").
     * Long pure-Latin passages are skipped (likely a non-Vietnamese sub file).
     */
    private fun shouldNarrateText(text: String): Boolean {
        if (looksVietnamese(text)) return true
        if (hasNonLatinScript(text)) return true
        val letters = text.count { it.isLetter() }
        return letters in 1..39 && text.all { !it.isLetter() || it in 'A'..'Z' || it in 'a'..'z' }
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

    fun release() {
        released = true
        val e = engine
        engine = null
        triedCreateEngine = false
        runCatching { e?.release() }
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady.set(false)
        speaking.set(false)
        unduckPlayer()
    }

    /**
     * Speaks [text], speeding up to fit [windowSec] when known so a long line is
     * fully read without bleeding into the next cue. Edge neural voice first,
     * device TTS as offline fallback.
     */
    private fun speakResolved(
        text: String,
        rateWindowSec: Float?,
        budgetMs: Long?,
        cutOnNewer: Boolean,
    ) {
        val rate = chooseRatePercent(text.length, rateWindowSec)
        Log.i(TAG, "speakResolved rate=$rate len=${text.length} win=$rateWindowSec bud=$budgetMs cut=$cutOnNewer")
        val engine = engineOrCreate()
        if (engine == null) {
            systemSpeak(text, rate)
            return
        }
        engine.setNarrationVolume(narrationVolume)
        try {
            engine.speak(
                text,
                narrationVoice,
                rate,
                cutOnNewer = cutOnNewer,
                budgetMs = budgetMs,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Narration speak failed, falling back to system TTS", t)
            systemSpeak(text, rate)
        }
    }

    private fun systemSpeak(text: String, rate: Int) {
        if (!ttsReady.get()) return
        if (vietnameseVoiceStatus != VietnameseVoiceStatus.READY) return
        speakFlushing(text, rate)
    }

    /**
     * Picks an SSML rate bump so the line fits its display window.
     * Returns 0..60 (%). Unknown window -> natural rate.
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

    /** Language of the currently selected text track, or null if none/unknown. */
    private fun selectedTextTrackLanguage(): String? {
        val groups = try {
            player.currentTracks.groups
        } catch (_: Exception) {
            return null
        }
        for (group in groups) {
            if (group.type != C.TRACK_TYPE_TEXT || !group.isSelected) continue
            for (i in 0 until group.length) {
                group.getTrackFormat(i).language?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    private fun notifySpeechStart() {
        speaking.set(true)
        duckPlayer()
    }

    private fun notifySpeechEnd() {
        speaking.set(false)
        unduckPlayer()
    }

    private fun speakFlushing(text: String, rate: Int) {
        val engine = tts ?: return
        val utteranceId = "nuvio-tts-${utteranceSeq.incrementAndGet()}-${UUID.randomUUID()}"
        try {
            runCatching { engine.setSpeechRate(1f + rate / 100f) }
            val params = android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, narrationVolume)
            }
            @Suppress("DEPRECATION")
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } catch (t: Throwable) {
            Log.e(TAG, "System TTS speak failed", t)
            unduckPlayer()
        }
    }

    private fun stopSpeaking() {
        runCatching { engine?.stop() }
        runCatching { tts?.stop() }
        lastSpokenText = null
        speaking.set(false)
        unduckPlayer()
    }

    private fun duckPlayer() {
        // Duck the movie a bit deeper than the user setting so the narration sits
        // clearly above the mix even at moderate duck levels.
        fadePlayerVolumeTo((duckLevel * 0.25f).coerceAtLeast(0.02f))
    }

    private fun unduckPlayer() {
        if (speaking.get()) return
        fadePlayerVolumeTo(1f)
    }

    /**
     * Smooth ~300ms volume ramp so narration ducking doesn't thump.
     * A newer fade cancels an older one; all player access stays on main.
     */
    private fun fadePlayerVolumeTo(target: Float) {
        val gen = fadeGeneration.incrementAndGet()
        mainHandler.post {
            if (released) return@post
            if (gen != fadeGeneration.get()) return@post
            val clamped = target.coerceIn(0f, 1f)
            val start = try {
                player.volume
            } catch (_: Throwable) {
                return@post
            }
            if (kotlin.math.abs(start - clamped) < 0.005f) {
                runCatching { player.volume = clamped }
                return@post
            }
            fun step(i: Int) {
                if (released) return
                if (gen != fadeGeneration.get()) return
                if (i >= FADE_STEPS) {
                    runCatching { player.volume = clamped }
                    return
                }
                val t = (i + 1) / FADE_STEPS.toFloat()
                val v = start + (clamped - start) * t
                runCatching { player.volume = v.coerceIn(0f, 1f) }
                mainHandler.postDelayed({ step(i + 1) }, FADE_STEP_MS)
            }
            step(0)
        }
    }

    companion object {
        private const val TAG = "NuvioTtsNarration"
        private const val TEST_SENTENCE = "Xin chào! Đây là giọng đọc thử tiếng Việt."
        private val VIETNAMESE = Locale("vi", "VN")
        private const val FADE_STEPS = 10
        private const val FADE_STEP_MS = 30L

        /** Conservative Vietnamese speech rate for window fitting (chars/sec). */
        private const val VI_CHARS_PER_SECOND = 12f

        /** Never squeeze speech into less than this window (seconds). */
        private const val MIN_SPEECH_WINDOW_SEC = 1.2f

        /** Baseline speed-up so narration keeps pace with the picture (percent). */
        private const val BASE_RATE_PERCENT = 10

        /** Max SSML speed-up applied to fit long lines (percent). */
        private const val MAX_RATE_BUMP_PERCENT = 50

        /** Safety margin (ms) kept before the next cue's start. */
        private const val NEXT_CUE_GUARD_MS = 120L

        /** Smoothing for the measured gap between embedded cues. */
        private const val CUE_INTERVAL_EMA_ALPHA = 0.35f

        /** Rate steps (percent) to keep the synth cache small. */
        private const val RATE_STEP = 10

        /** True when the player's selected text track is Vietnamese. */
        fun isSelectedSubtitleVietnamese(player: ExoPlayer): Boolean {
            val groups = try {
                player.currentTracks.groups
            } catch (_: Exception) {
                return false
            }
            for (group in groups) {
                if (group.type != C.TRACK_TYPE_TEXT || !group.isSelected) continue
                for (i in 0 until group.length) {
                    val language = group.getTrackFormat(i).language ?: continue
                    if (isVietnameseLanguageCode(language)) return true
                }
            }
            return false
        }

        fun isVietnameseLanguageCode(code: String): Boolean {
            val normalized = code.trim().lowercase(Locale.US)
            if (normalized.isEmpty()) return false
            val base = normalized.substringBefore('-').substringBefore('_')
            return base == "vi" || base == "vie" || normalized.startsWith("vi-") ||
                normalized == "vietnamese" || normalized.startsWith("vietnamese")
        }

        /**
         * Heuristic: does this text look like Vietnamese? Used for sidecar/external
         * subtitles that carry no language tag.
         * - Strict set (đ ă ơ ư + case variants) never appears in English/French/Spanish.
         * - Otherwise requires a high density of Vietnamese accented vowels.
         */
        fun looksVietnamese(text: String): Boolean {
            if (text.any { it in VI_STRICT }) return true
            var letters = 0
            var accented = 0
            for (char in text) {
                if (!char.isLetter()) continue
                letters++
                if (char in VI_BROAD_ACCENTS) accented++
            }
            if (letters < 2) return false
            return accented.toFloat() / letters.toFloat() >= 0.08f
        }

        private const val VI_STRICT = "đĐăĂơƠưƯ"

        private const val VI_BROAD_ACCENTS =
            "àáạảãâầấậẩẫắằẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗớờởỡợùúụủũưừứựửữỳýỵỷỹ" +
                "ÀÁẠẢÃÂẦẤẬẨẪẮẰẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖỚỜỞỠỢÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸ"

        /** Strips ASS override tags, HTML-like tags and cue-positioning noise for TTS. */
        fun cleanCueText(cue: Cue): String? {
            var text = cue.text?.toString() ?: return null
            text = text.replace(Regex("\\{[^}]*\\}"), " ")
            // ASS soft/hard line breaks and hard spaces (\N, \n, \h) are literal
            // backslash sequences, not real newlines; drop them or they get read.
            text = text.replace(Regex("\\\\[Nnh]"), " ")
            text = text.replace(Regex("<[^>]*>"), " ")
            text = text.replace(Regex("[\\r\\n]+"), " ")
            text = text.replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return null
            if (text.startsWith("♪") || text.startsWith("[") && text.endsWith("]")) return null
            return text
        }

        /**
         * Text of every line in the current cue set, joined. A two-line subtitle
         * must be narrated in full -- taking only the first line dropped words.
         */
        fun combineCueTexts(cues: List<Cue>): String? {
            val parts = cues.mapNotNull { cleanCueText(it) }.filter { it.isNotBlank() }
            if (parts.isEmpty()) return null
            return parts.joinToString(" ")
        }
    }
}
