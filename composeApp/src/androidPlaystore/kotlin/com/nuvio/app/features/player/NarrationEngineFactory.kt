package com.nuvio.app.features.player

import android.content.Context

/**
 * Play Store build: the 272 MB offline model is not bundled, so narration falls
 * back to the device's built-in Vietnamese TTS engine (if installed).
 */
object NarrationEngineFactory {
    fun create(
        context: Context,
        onSpeechStart: () -> Unit,
        onSpeechEnd: () -> Unit,
        onError: (text: String, rate: Int) -> Unit,
    ): NarrationTtsEngine? = null
}
