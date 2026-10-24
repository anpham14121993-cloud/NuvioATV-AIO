package com.nuvio.tv.core.player.tts

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NarrationController(
    private val appContext: Context,
    private val playerProvider: () -> ExoPlayer?,
) {

    enum class SystemTtsStatus {
        UNKNOWN,
        READY,
        NOT_AVAILABLE,
    }

    val status = MutableStateFlow(NarrationEngineStatus.LOADING)
    val lastCue = MutableStateFlow<String?>(null)
    val lastError = MutableStateFlow<String?>(null)

    @Volatile
    var systemTtsStatus: SystemTtsStatus = SystemTtsStatus.UNKNOWN
        private set

    private var engine: NarrationTtsEngine? = null
    private var triedCreateEngine = false

    @Volatile
    var narrationVoice: String = "Thùy Dung"

    private val ttsReady = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val utteranceSeq = AtomicInteger(0)

    @Volatile
    private var enabled = false

    @Volatile
    var narrationVolume: Float = 0.9f
        private set

    @Volatile
    var duckLevelPercent: Int = 50
        set(value) {
            field = value.coerceIn(0, 100)
        }

    @Volatile
    private var activeSubtitleIsVietnamese = false

    private val fadeGeneration = AtomicInteger(0)

    @Volatile
    private var lastSpokenText: String? = null
    private var lastCueAtMs = 0L
    private var cueIntervalEmaMs = 0f

    private var engineWarmedUp = false

    private var tts: TextToSpeech? = null
    private var released = false

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

    private fun engineOrCreate(): NarrationTtsEngine? {
        engine?.let { return it }
        if (triedCreateEngine) return null
        triedCreateEngine = true
        return runCatching {
            NarrationDiag.log(appContext, "ctrl: create offline engine")
            NarrationEngineFactory.create(
                context = appContext,
                onSpeechStart = { notifySpeechStart() },
                onSpeechEnd = { notifySpeechEnd() },
                onError = { failedText, failedRate -> systemSpeak(failedText, failedRate) },
                onStatus = { state -> status.value = state },
            )
        }.onFailure {
            NarrationDiag.log(appContext, "ctrl: engine create failed ${it.javaClass.simpleName}")
        }.getOrNull().also { engine = it }
    }

    fun start() {
        if (tts != null) return
        NarrationDiag.log(appContext, "ctrl: start system tts")
        try {
            tts = TextToSpeech(appContext) { status ->
                runCatching { onTtsInit(status) }
            }
        } catch (t: Throwable) {
            Log.e("NuvioNarration", "System TTS init failed", t)
            NarrationDiag.log(appContext, "tts: create failed ${t.javaClass.simpleName}")
            tts = null
            systemTtsStatus = SystemTtsStatus.NOT_AVAILABLE
        }
    }

    private fun onTtsInit(status: Int) {
        NarrationDiag.log(appContext, "tts: init=$status")
        if (status != TextToSpeech.SUCCESS) {
            systemTtsStatus = SystemTtsStatus.NOT_AVAILABLE
            return
        }
        val engine = tts ?: return
        val availability = try {
            engine.setLanguage(Locale("vi", "VN"))
        } catch (_: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        NarrationDiag.log(appContext, "tts: vi-VN availability=$availability")
        if (availability == TextToSpeech.LANG_MISSING_DATA ||
            availability == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            systemTtsStatus = SystemTtsStatus.NOT_AVAILABLE
            NarrationDiag.log(appContext, "tts: vi-VN missing")
            return
        }
        try {
            engine.setOnUtteranceProgressListener(progressListener)
        } catch (t: Throwable) {
            Log.e("NuvioNarration", "setOnUtteranceProgressListener failed", t)
            systemTtsStatus = SystemTtsStatus.NOT_AVAILABLE
            return
        }
        ttsReady.set(true)
        systemTtsStatus = SystemTtsStatus.READY
    }

    fun setEnabled(value: Boolean) {
        if (enabled != value) Log.i("NuvioNarration", "narration enabled=$value")
        enabled = value
        if (!value) {
            stopSpeaking()
            unduckPlayer()
        } else {
            start()
        }
    }

    fun setVolumePercent(volumePercent: Int) {
        narrationVolume = (volumePercent.coerceIn(0, 100) / 100f).coerceIn(0f, 1f)
        engine?.setNarrationVolume(narrationVolume)
        runCatching { tts?.setSpeechRate(1f) }
    }

    fun setActiveSubtitleIsVietnamese(value: Boolean) {
        if (activeSubtitleIsVietnamese == value) return
        activeSubtitleIsVietnamese = value
        if (!value) stopSpeaking()
    }

    fun onCueText(rawText: String?, windowSec: Float? = null) {
        if (!enabled || released) return
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
                    cueIntervalEmaMs + (deltaMs - cueIntervalEmaMs) * NarrationController.CUE_INTERVAL_EMA_ALPHA
                }
            }
        }
        lastSpokenText = text
        val effectiveWindow = windowSec ?: cueIntervalEmaMs
            .takeIf { it > 0f }
            ?.let { it / 1000f }
        val budgetMs = effectiveWindow?.let { sec ->
            ((sec * 1000f).toLong() - NarrationController.NEXT_CUE_GUARD_MS).coerceAtLeast(150L)
        }
        speakResolved(text, effectiveWindow, budgetMs, cutOnNewer = true)
    }

    fun prefetch(text: String) {
        if (!enabled || released) return
        val clean = cleanText(text) ?: return
        val e = engineOrCreate() ?: return
        if (!engineWarmedUp) {
            runCatching { e.warmUp() }
            engineWarmedUp = true
        }
        val rate = chooseRatePercent(clean.length, null)
        e.prefetch(clean, narrationVoice, rate)
    }

    private fun speakResolved(
        text: String,
        rateWindowSec: Float?,
        budgetMs: Long?,
        cutOnNewer: Boolean,
    ) {
        val rate = chooseRatePercent(text.length, rateWindowSec)
        Log.i("NuvioNarration", "speakResolved rate=$rate len=${text.length} win=$rateWindowSec bud=$budgetMs cut=$cutOnNewer")
        NarrationDiag.log(appContext, "speak: len=${text.length} rate=$rate w=$rateWindowSec b=$budgetMs")
        val e = engineOrCreate()
        if (e == null) {
            NarrationDiag.log(appContext, "speak: no engine -> system tts")
            systemSpeak(text, rate)
            return
        }
        e.setNarrationVolume(narrationVolume)
        try {
            e.speak(
                text = text,
                voiceApiName = narrationVoice,
                ratePercent = rate,
                cutOnNewer = cutOnNewer,
                budgetMs = budgetMs,
            )
        } catch (t: Throwable) {
            Log.e("NuvioNarration", "Narration speak failed, falling back to system TTS", t)
            NarrationDiag.log(appContext, "speak: threw ${t.javaClass.simpleName} -> system tts")
            systemSpeak(text, rate)
        }
    }

    private fun systemSpeak(text: String, rate: Int) {
        if (!ttsReady.get()) {
            NarrationDiag.log(appContext, "sysTts: not ready (${systemTtsStatus})")
            return
        }
        if (systemTtsStatus != SystemTtsStatus.READY) return
        NarrationDiag.log(appContext, "sysTts: speak len=${text.length}")
        speakFlushing(text, rate)
    }

    private fun chooseRatePercent(charCount: Int, windowSec: Float?): Int {
        val fit = if (windowSec == null || windowSec <= 0f) {
            0
        } else {
            val window = windowSec?.coerceAtLeast(NarrationController.MIN_SPEECH_WINDOW_SEC) ?: NarrationController.MIN_SPEECH_WINDOW_SEC
            val estimated = charCount / NarrationController.VI_CHARS_PER_SECOND
            if (estimated <= window) 0 else (((estimated / window) - 1f) * 100f).toInt()
        }
        return (NarrationController.BASE_RATE_PERCENT + fit).coerceIn(0, NarrationController.MAX_RATE_BUMP_PERCENT) / NarrationController.RATE_STEP * NarrationController.RATE_STEP
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
            Log.e("NuvioNarration", "System TTS speak failed", t)
            NarrationDiag.log(appContext, "sysTts: threw ${t.javaClass.simpleName}")
            unduckPlayer()
        }
    }

    fun stopSpeaking() {
        runCatching { engine?.stop() }
        runCatching { tts?.stop() }
        lastSpokenText = null
        speaking.set(false)
        unduckPlayer()
    }

    fun release() {
        released = true
        enabled = false
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

    private fun duckPlayer() {
        val target = (duckLevelPercent / 100f * 0.25f).coerceAtLeast(0.02f)
        fadePlayerVolumeTo(target)
    }

    private fun unduckPlayer() {
        if (speaking.get()) return
        fadePlayerVolumeTo(1f)
    }

    private fun fadePlayerVolumeTo(target: Float) {
        val gen = fadeGeneration.incrementAndGet()
        mainHandler.post {
            val player = playerProvider() ?: return@post
            val start = player.volume
            val clamped = target.coerceIn(0f, 1f)
            if (kotlin.math.abs(start - clamped) < 0.01f) {
                runCatching { player.volume = clamped }
                return@post
            }
            fun step(index: Int) {
                if (fadeGeneration.get() != gen) return
                if (index >= NarrationController.FADE_STEPS) {
                    runCatching { player.volume = clamped }
                    return
                }
                val t = (index + 1).toFloat() / NarrationController.FADE_STEPS
                val value = start + (clamped - start) * t
                runCatching { player.volume = value.coerceIn(0f, 1f) }
                mainHandler.postDelayed({ step(index + 1) }, NarrationController.FADE_STEP_MS)
            }
            step(0)
        }
    }

    private fun cleanText(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        var text = rawText.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace("&", "&")
            .replace("<", "<")
            .replace(">", ">")
            .replace("\"", "\"")
            .replace("'", "'")
            .replace(Regex("\\{[^}]*\\}"), " ")
            .replace(Regex("\\\\[Nnh]"), " ")
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isEmpty()) return null
        if (text.startsWith("♪") || text.startsWith("[") && text.endsWith("]")) return null
        return text
    }

    private fun shouldNarrateText(text: String): Boolean {
        if (looksVietnamese(text)) return true
        if (hasNonLatinScript(text)) return true
        val letters = text.count { it.isLetter() }
        return letters in 1..39 && text.all { !it.isLetter() || it in 'A'..'Z' || it in 'a'..'z' }
    }

    private fun looksVietnamese(text: String): Boolean {
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

    companion object {
        private const val TAG = "NuvioNarration"
        const val FADE_STEPS = 10
        const val FADE_STEP_MS = 30L
        const val VI_CHARS_PER_SECOND = 12f
        const val MIN_SPEECH_WINDOW_SEC = 1.2f
        const val BASE_RATE_PERCENT = 10
        const val MAX_RATE_BUMP_PERCENT = 50
        const val NEXT_CUE_GUARD_MS = 120L
        const val CUE_INTERVAL_EMA_ALPHA = 0.35f
        const val RATE_STEP = 10

        private const val VI_STRICT = "đĐăĂơƠưƯ"
        private const val VI_BROAD_ACCENTS =
            "àáạảãâầấậẩẫắằẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗớờởỡợùúụủũưừứựửữỳýỵỷỹ" +
                "ÀÁẠẢÃÂẦẤẬẨẪẮẰẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖỚỜỞỠỢÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸ"
    }
}