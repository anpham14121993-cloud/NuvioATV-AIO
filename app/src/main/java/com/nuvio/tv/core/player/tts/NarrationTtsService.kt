package com.nuvio.tv.core.player.tts

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

/**
 * Runs the offline VieNeu engine in its own process.
 *
 * The ONNX runtime and the G2P native library can abort the whole process on
 * weak TV boxes (native memory, unsupported instructions). Keeping them in a
 * separate process means such a crash never takes the player down: the client
 * only sees the binding die and reports the failure.
 */
class NarrationTtsService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: NarrationTtsEngine? = null
    private var client: Messenger? = null

    private val messenger = Messenger(object : Handler(mainHandler.looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_HELLO -> {
                    client = msg.replyTo
                    reply(MSG_READY)
                }
                MSG_WARMUP -> {
                    ensureEngine()
                }
                MSG_SPEAK -> {
                    val data = msg.data ?: Bundle()
                    val text = data.getString(ARG_TEXT).orEmpty()
                    val voice = data.getString(ARG_VOICE).orEmpty()
                    val rate = data.getInt(ARG_RATE, 0)
                    val budget = data.getLong(ARG_BUDGET, 0L).takeIf { it > 0L }
                    speak(text, voice, rate, budget)
                }
                MSG_PREFETCH -> {
                    val data = msg.data ?: Bundle()
                    prefetch(data.getString(ARG_TEXT).orEmpty(), data.getString(ARG_VOICE).orEmpty())
                }
                MSG_STOP -> {
                    runCatching { engine?.stop() }
                }
                MSG_SET_VOLUME -> {
                    val volume = msg.data?.getFloat(ARG_VOLUME, 1f) ?: 1f
                    currentVolume = volume
                    runCatching { engine?.setNarrationVolume(volume) }
                }
                MSG_RELEASE -> {
                    releaseEngine()
                    reply(MSG_RELEASED)
                    stopSelf()
                }
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        releaseEngine()
        super.onDestroy()
    }

    // ── engine ────────────────────────────────────────────────────────────────

    private fun ensureEngine(): NarrationTtsEngine? {
        engine?.let { return it }
        return try {
            val created = NarrationEngineFactory.create(
                context = applicationContext,
                onSpeechStart = { reply(MSG_SPEECH_START) },
                onSpeechEnd = { reply(MSG_SPEECH_END) },
                onError = { detail, _ -> reply(MSG_ERROR, Bundle().apply { putString(ARG_DETAIL, detail) }) },
                onStatus = { state ->
                    reply(MSG_STATUS, Bundle().apply {
                        putString(ARG_STATE, state.name)
                    })
                },
            )
            engine = created
            // Load the 261 MB of weights off the main thread of this process.
            runCatching { created.warmUp() }
            created
        } catch (t: Throwable) {
            Log.e(TAG, "could not create the narration engine", t)
            reply(MSG_ERROR, Bundle().apply { putString(ARG_DETAIL, "engine: ${t.javaClass.simpleName}") })
            null
        }
    }

    private fun speak(text: String, voice: String, rate: Int, budgetMs: Long?) {
        if (text.isBlank()) return
        val created = ensureEngine() ?: return
        created.setNarrationVolume(currentVolume)
        created.speak(
            text = text,
            voiceApiName = voice,
            ratePercent = rate,
            cutOnNewer = true,
            budgetMs = budgetMs,
            onResult = { ok, detail ->
                if (!ok) {
                    reply(MSG_ERROR, Bundle().apply { putString(ARG_DETAIL, "speak: $detail") })
                }
            },
        )
    }

    private fun prefetch(text: String, voice: String) {
        if (text.isBlank()) return
        val created = ensureEngine() ?: return
        created.prefetch(text, voice, 0)
    }

    private fun releaseEngine() {
        runCatching { engine?.release() }
        engine = null
    }

    private var currentVolume = 1f

    private fun reply(what: Int, data: Bundle? = null) {
        val target = client ?: return
        val message = Message.obtain(null, what)
        message.data = data
        runCatching { target.send(message) }
    }

    companion object {
        private const val TAG = "NarrationTtsService"

        const val MSG_HELLO = 1
        const val MSG_READY = 2
        const val MSG_WARMUP = 3
        const val MSG_SPEAK = 4
        const val MSG_PREFETCH = 5
        const val MSG_STOP = 6
        const val MSG_SET_VOLUME = 7
        const val MSG_RELEASE = 8
        const val MSG_STATUS = 9
        const val MSG_SPEECH_START = 10
        const val MSG_SPEECH_END = 11
        const val MSG_ERROR = 12
        const val MSG_RELEASED = 13

        const val ARG_TEXT = "text"
        const val ARG_VOICE = "voice"
        const val ARG_RATE = "rate"
        const val ARG_BUDGET = "budget"
        const val ARG_VOLUME = "volume"
        const val ARG_DETAIL = "detail"
        const val ARG_STATE = "state"
    }
}
