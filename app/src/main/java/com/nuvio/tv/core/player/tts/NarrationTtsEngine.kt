package com.nuvio.tv.core.player.tts

/**
 * Engine surface the narration controller needs. Implemented by the bundled
 * offline VieNeu engine, so the controller never hard-codes one synthesis path.
 */
interface NarrationTtsEngine {
    /** Pre-warms the engine (model load) so the first cue is instant. */
    fun warmUp()

    /** Narration loudness in 0..1; applied on top of the ducked movie mix. */
    fun setNarrationVolume(volume01: Float)

    /**
     * Synthesizes and plays [text]; work happens off the main thread. A new call
     * invalidates any in-flight utterance. [ratePercent] speeds playback up to
     * fit [budgetMs]. [budgetMs] is advisory: on hardware where synthesis is
     * slower than playback the engine plays the whole line and lets the next
     * line cut it, instead of truncating the sentence and leaving silence.
     */
    fun speak(
        text: String,
        voiceApiName: String,
        ratePercent: Int,
        cutOnNewer: Boolean = false,
        budgetMs: Long? = null,
        onResult: ((ok: Boolean, detail: String) -> Unit)? = null,
    )

    /** Pre-synthesizes [text] into the cache; never interrupts playback. */
    fun prefetch(text: String, voiceApiName: String, ratePercent: Int, targetRam: Boolean = true)

    /** Stops playback immediately and drops pending requests. */
    fun stop()

    /** Releases all resources. */
    fun release()

    /** Clears any pending background prefetch queue (e.g. on seek). */
    fun clearPrefetchQueue() {}
}
