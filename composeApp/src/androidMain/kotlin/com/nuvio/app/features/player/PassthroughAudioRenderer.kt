package com.nuvio.app.features.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import java.nio.ByteBuffer

/**
 * Sends the encoded audio bitstream straight to the amplifier instead of
 * decoding it to PCM.
 *
 * Only Dolby Digital (AC-3) and Dolby Digital Plus (E-AC-3) are supported:
 * the samples are written into an [AudioTrack] created with the matching
 * encoding and the framework forwards them to the HDMI / ARC receiver.
 *
 * The renderer only claims a track when the device reports support for the
 * encoding; otherwise playback keeps using the regular software decoder.
 */
@UnstableApi
internal class PassthroughAudioRenderer(
    private val support: PassthroughSupport,
) : BaseRenderer(C.TRACK_TYPE_AUDIO) {

    private var audioTrack: AudioTrack? = null
    private var activeFormat: PassthroughFormat? = null
    private var endOfStream = false
    private var lastPositionUs = C.TIME_UNSET
    private val formatHolder = FormatHolder()

    override fun getName(): String = "NuvioPassthroughAudioRenderer"

    override fun supportsFormat(format: Format): Int {
        val passthroughFormat = PassthroughFormat.forMimeType(format.sampleMimeType)
        if (passthroughFormat == null) return C.FORMAT_UNSUPPORTED_TYPE
        if (!support.supports(passthroughFormat)) return C.FORMAT_UNSUPPORTED_TYPE
        return C.FORMAT_HANDLED
    }

    override fun supportsMixedMimeTypeAdaptation(): Int = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED

    override fun isReady(): Boolean = true

    override fun isEnded(): Boolean = endOfStream

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (endOfStream) return
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
        val result = readSource(formatHolder, buffer, 0)
        when (result) {
            C.RESULT_BUFFER_READ -> {
                lastPositionUs = buffer.timeUs
                if (buffer.isEndOfStream) {
                    endOfStream = true
                    releaseTrack()
                    return
                }
                writeSample(buffer)
            }

            C.RESULT_FORMAT_READ -> onFormatChanged(formatHolder.format)

            else -> Unit
        }
    }

    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        super.onEnabled(joining, mayRenderStartOfStream)
        endOfStream = false
        lastPositionUs = C.TIME_UNSET
    }

    override fun onStreamChanged(
        formats: Array<out Format>,
        mediaStartPositionUs: Long,
        mediaEndPositionUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        super.onStreamChanged(formats, mediaStartPositionUs, mediaEndPositionUs, mediaPeriodId)
        onFormatChanged(formats.firstOrNull())
    }

    override fun onDisabled() {
        releaseTrack()
        super.onDisabled()
    }

    override fun onReset() {
        releaseTrack()
        endOfStream = false
        super.onReset()
    }

    override fun onRelease() {
        releaseTrack()
        super.onRelease()
    }

    private fun onFormatChanged(format: Format?) {
        val passthroughFormat = PassthroughFormat.forMimeType(format?.sampleMimeType)
        if (passthroughFormat == null) {
            releaseTrack()
            return
        }
        if (passthroughFormat == activeFormat && audioTrack != null) return
        if (!support.supports(passthroughFormat)) {
            throw createRendererException(
                IllegalStateException("Passthrough is not supported for ${passthroughFormat.mimeType}"),
                format,
                PlaybackException.ERROR_CODE_NOT_SUPPORTED,
            )
        }
        releaseTrack()
        val track = runCatching { createTrack(passthroughFormat, format) }.getOrElse { error ->
            throw createRendererException(
                IllegalStateException("Could not open a passthrough AudioTrack: ${error.message}", error),
                format,
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            )
        }
        audioTrack = track
        activeFormat = passthroughFormat
        Log.i(TAG, "Passthrough enabled for ${passthroughFormat.mimeType}")
    }

    private fun createTrack(format: PassthroughFormat, source: Format?): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(format.audioEncoding)
            .setSampleRate(source?.sampleRate?.takeIf { it > 0 } ?: 48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
            .build()
        val minimumBuffer = AudioTrack.getMinBufferSize(
            audioFormat.sampleRate,
            audioFormat.channelMask,
            audioFormat.encoding,
        ).coerceAtLeast(64 * 1024)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minimumBuffer * 2)
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack was not initialized" }
        track.play()
        return track
    }

    private fun writeSample(buffer: DecoderInputBuffer) {
        val track = audioTrack ?: return
        val data: ByteBuffer = buffer.data ?: return
        val size = data.remaining()
        if (size <= 0) return
        val bytes = ByteArray(size)
        data.get(bytes)
        val written = runCatching {
            track.write(bytes, 0, size, AudioTrack.WRITE_BLOCKING)
        }.getOrDefault(-1)
        if (written < 0) {
            Log.w(TAG, "Passthrough write failed; releasing the track")
            releaseTrack()
        }
    }

    private fun releaseTrack() {
        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        audioTrack = null
        activeFormat = null
    }

    private companion object {
        const val TAG = "PassthroughAudio"
    }
}
