package com.nuvio.tv.core.player.tts

import android.content.Context

/**
 * Creates the primary narration engine (Edge TTS online with neural voices).
 * If synthesis fails or device is offline, NarrationController dispatches to
 * Fallback 1 (Google/System TTS) or Fallback 2 (Piper TTS).
 */
object NarrationEngineFactory {
    fun create(
        context: Context,
        onSpeechStart: () -> Unit,
        onSpeechEnd: () -> Unit,
        onError: (text: String, rate: Int) -> Unit,
        onStatus: (NarrationEngineStatus) -> Unit = {},
        onStage: (String) -> Unit = {},
    ): NarrationTtsEngine = EdgeNarrationEngine(
        appContext = context.applicationContext,
        onSpeechStart = onSpeechStart,
        onSpeechEnd = onSpeechEnd,
        onError = onError,
        onStatus = onStatus,
        onStage = onStage,
    )
}

/** Progress of the narration engine so the UI can explain state. */
enum class NarrationEngineStatus {
    /** The engine is connecting or preheating. */
    LOADING,

    /** The engine is ready and can synthesize. */
    READY,

    /** The engine could not be loaded; the detail carries the reason. */
    FAILED
}
