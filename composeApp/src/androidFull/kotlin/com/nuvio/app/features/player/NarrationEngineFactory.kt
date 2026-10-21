package com.nuvio.app.features.player

import android.content.Context

/** Full distribution: bundled offline VieNeu engine, no network required. */
object NarrationEngineFactory {
    fun create(
        context: Context,
        onSpeechStart: () -> Unit,
        onSpeechEnd: () -> Unit,
        onError: (text: String, rate: Int) -> Unit,
    ): NarrationTtsEngine =
        VieneuNarrationEngine(
            context.applicationContext,
            onSpeechStart = onSpeechStart,
            onSpeechEnd = onSpeechEnd,
            onError = onError,
        )
}
