package com.nuvio.tv.core.player.tts

import android.content.Context

/** Bundled offline VieNeu engine; no network required. */
object NarrationEngineFactory {
    fun create(
        context: Context,
        onSpeechStart: () -> Unit,
        onSpeechEnd: () -> Unit,
        onError: (text: String, rate: Int) -> Unit,
        onStatus: (NarrationEngineStatus) -> Unit = {},
        onStage: (String) -> Unit = {},
    ): NarrationTtsEngine = VieneuNarrationEngine(
        context.applicationContext,
        onSpeechStart = onSpeechStart,
        onSpeechEnd = onSpeechEnd,
        onError = onError,
        onStatus = onStatus,
        onStage = onStage,
    )
}

/** Progress of the offline model so the UI can explain a silent narration. */
enum class NarrationEngineStatus {
    /** The models are being copied out of the APK and the engine is loading. */
    LOADING,

    /** The engine is ready and can synthesize. */
    READY,

    /** The engine could not be loaded; the detail carries the reason. */
    FAILED
}
