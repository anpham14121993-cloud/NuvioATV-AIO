package com.nuvio.app.features.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Encodings that can be sent to an amplifier as a bitstream instead of being
 * decoded to PCM.
 */
enum class PassthroughFormat(val mimeType: String, val audioEncoding: Int) {
    AC3("audio/ac3", AudioFormat.ENCODING_AC3),
    EAC3("audio/eac3", AudioFormat.ENCODING_E_AC3),
    ;

    companion object {
        fun forMimeType(mimeType: String?): PassthroughFormat? = when (mimeType?.lowercase()) {
            "audio/ac3", "audio/ac-3" -> AC3
            "audio/eac3", "audio/e-ac3", "audio/eac3-joc", "audio/e-ac3-joc" -> EAC3
            else -> null
        }
    }
}

/**
 * Which encodings the connected device accepts as a bitstream.
 */
data class PassthroughSupport(
    val ac3: Boolean = false,
    val eac3: Boolean = false,
) {
    val any: Boolean get() = ac3 || eac3

    fun supports(format: PassthroughFormat): Boolean = when (format) {
        PassthroughFormat.AC3 -> ac3
        PassthroughFormat.EAC3 -> eac3
    }
}

/**
 * Detects whether the TV / box can play Dolby Digital and Dolby Digital Plus
 * as a bitstream (passthrough) over HDMI or ARC.
 */
object AudioPassthroughProbe {

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    @Volatile
    private var cached: PassthroughSupport? = null

    fun support(context: Context): PassthroughSupport {
        cached?.let { return it }
        val fresh = PassthroughSupport(
            ac3 = supportsEncoding(context, AudioFormat.ENCODING_AC3),
            eac3 = supportsEncoding(context, AudioFormat.ENCODING_E_AC3),
        )
        cached = fresh
        return fresh
    }

    fun invalidate() {
        cached = null
    }

    private fun supportsEncoding(context: Context, encoding: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                .build()
            val direct = runCatching {
                AudioTrack.isDirectPlaybackSupported(format, attributes)
            }.getOrDefault(false)
            if (direct) return true
        }
        return hdmiPlugEncodings(context)?.contains(encoding) == true
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun hdmiPlugEncodings(context: Context): IntArray? = runCatching {
        val report = context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
            ?: return@runCatching null
        val plugState = report.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1)
        if (plugState != 1) return@runCatching null
        report.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS)
    }.getOrNull()
}

actual fun audioPassthroughSupportedEncodings(): List<String> {
    val context = com.nuvio.app.core.ui.PlatformAppContext.value ?: return emptyList()
    val support = AudioPassthroughProbe.support(context)
    return buildList {
        if (support.ac3) add("AC-3")
        if (support.eac3) add("E-AC-3")
    }
}
