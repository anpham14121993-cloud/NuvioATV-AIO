package com.nuvio.tv.core.player.tts

import android.content.Context

/** Bundled offline VieNeu engine; no network required. */
object NarrationEngineFactory {
    fun create(
        context: Context,
        onSpeechStart: () -> Unit,
        onSpeechEnd: () -> Unit,
        onError: (text: String, rate: Int) -> Unit,
    ): NarrationTtsEngine = VieneuNarrationEngine(
        context.applicationContext,
        onSpeechStart = onSpeechStart,
        onSpeechEnd = onSpeechEnd,
        onError = onError,
    )
}
