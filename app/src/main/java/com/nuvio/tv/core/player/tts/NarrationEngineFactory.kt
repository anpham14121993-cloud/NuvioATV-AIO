package com.nuvio.tv.core.player.tts

import android.content.Context

/**
 * Creates the primary narration engine (Offline Piper TTS with ONNX Runtime).
 * Completely local, zero internet latency, no WebSocket roundtrips, and no
 * missed subtitle cues.
 */
object NarrationEngineFactory {
    fun create(
        context: Context,
        onSpeechStart: () -> Unit,
        onSpeechEnd: () -> Unit,
        onError: (text: String, rate: Int) -> Unit,
        onStatus: (NarrationEngineStatus) -> Unit = {},
        onStage: (String) -> Unit = {},
    ): NarrationTtsEngine = PiperNarrationEngine(
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
    /** The engine is loading or downloading models. */
    LOADING,

    /** The engine is ready and can synthesize. */
    READY,

    /** The engine could not be loaded; the detail carries the reason. */
    FAILED
}
