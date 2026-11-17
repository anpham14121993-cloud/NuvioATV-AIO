@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import java.io.EOFException
import java.util.PriorityQueue
import kotlin.math.max

/**
 * Thread-safe rolling buffer holding demuxed embedded subtitle cues that arrived more
 * than 25 seconds ahead of the playhead so they still get pre-synthesized in time as
 * playback advances through long ExoPlayer read-ahead buffers.
 */
internal class EmbeddedNarrationPrefetchBuffer(
    private val isEnabled: () -> Boolean,
    private val selectedTrackMatcher: (Format?) -> Boolean,
    private val onUpcomingCue: (text: String, windowSec: Float?) -> Unit,
) {
    companion object {
        const val LOOKAHEAD_US = 25_000_000L
        const val PAST_TOLERANCE_US = 200_000L
        private const val MAX_QUEUED_CUES = 400
    }

    private data class BufferedCue(
        val startUs: Long,
        val windowSec: Float?,
        val text: String,
        val format: Format?,
    ) : Comparable<BufferedCue> {
        override fun compareTo(other: BufferedCue): Int = startUs.compareTo(other.startUs)
    }

    private val lock = Any()
    private val pending = PriorityQueue<BufferedCue>()

    fun submit(startUs: Long, windowSec: Float?, text: String, format: Format?, playPosUs: Long) {
        if (!isEnabled() || !selectedTrackMatcher(format)) return
        if (startUs < playPosUs - PAST_TOLERANCE_US) return
        if (startUs <= playPosUs + LOOKAHEAD_US) {
            onUpcomingCue(text, windowSec)
            return
        }
        synchronized(lock) {
            if (pending.size >= MAX_QUEUED_CUES) {
                pending.poll()
            }
            pending.add(BufferedCue(startUs, windowSec, text, format))
        }
    }

    fun drainUpTo(playPosUs: Long) {
        if (!isEnabled()) return
        val ready = mutableListOf<BufferedCue>()
        val maxUs = playPosUs + LOOKAHEAD_US
        val minUs = playPosUs - PAST_TOLERANCE_US
        synchronized(lock) {
            while (true) {
                val head = pending.peek() ?: break
                if (head.startUs > maxUs) break
                pending.poll()
                if (head.startUs >= minUs) {
                    ready.add(head)
                }
            }
        }
        for (item in ready) {
            if (selectedTrackMatcher(item.format)) {
                onUpcomingCue(item.text, item.windowSec)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            pending.clear()
        }
    }
}

/**
 * Observes demuxed Media3 subtitle cues ahead of playback while forwarding all extractor
 * output untouched, pre-synthesizing upcoming embedded lines before they appear on screen.
 */
internal class NarrationPrefetchExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val isEnabled: () -> Boolean,
    private val currentPositionUs: () -> Long,
    private val selectedTrackMatcher: (Format?) -> Boolean,
    private val buffer: EmbeddedNarrationPrefetchBuffer,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { index ->
            PrefetchObservingExtractor(
                delegate = extractors[index],
                isEnabled = isEnabled,
                currentPositionUs = currentPositionUs,
                selectedTrackMatcher = selectedTrackMatcher,
                buffer = buffer,
            )
        }
}

private class PrefetchObservingExtractor(
    private val delegate: Extractor,
    private val isEnabled: () -> Boolean,
    private val currentPositionUs: () -> Long,
    private val selectedTrackMatcher: (Format?) -> Boolean,
    private val buffer: EmbeddedNarrationPrefetchBuffer,
) : Extractor {
    private val textTrackOutputs = mutableListOf<PrefetchTextTrackOutput>()

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)
    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.getSniffFailureDetails()

    override fun init(output: ExtractorOutput) {
        delegate.init(
            PrefetchObservingExtractorOutput(
                delegate = output,
                trackOutputs = textTrackOutputs,
                isEnabled = isEnabled,
                currentPositionUs = currentPositionUs,
                selectedTrackMatcher = selectedTrackMatcher,
                buffer = buffer,
            )
        )
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        for (i in textTrackOutputs.indices) {
            textTrackOutputs[i].resetBuffer()
        }
        buffer.clear()
        delegate.seek(position, timeUs)
    }

    override fun release() {
        textTrackOutputs.clear()
        delegate.release()
    }

    override fun getUnderlyingImplementation(): Extractor = delegate.getUnderlyingImplementation()
}

private class PrefetchObservingExtractorOutput(
    private val delegate: ExtractorOutput,
    private val trackOutputs: MutableList<PrefetchTextTrackOutput>,
    private val isEnabled: () -> Boolean,
    private val currentPositionUs: () -> Long,
    private val selectedTrackMatcher: (Format?) -> Boolean,
    private val buffer: EmbeddedNarrationPrefetchBuffer,
) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        val output = delegate.track(id, type)
        if (type != C.TRACK_TYPE_TEXT) return output
        return PrefetchTextTrackOutput(
            delegate = output,
            isEnabled = isEnabled,
            currentPositionUs = currentPositionUs,
            selectedTrackMatcher = selectedTrackMatcher,
            buffer = buffer,
        ).also { trackOutputs.add(it) }
    }

    override fun endTracks() = delegate.endTracks()
    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

private class PrefetchTextTrackOutput(
    private val delegate: TrackOutput,
    private val isEnabled: () -> Boolean,
    private val currentPositionUs: () -> Long,
    private val selectedTrackMatcher: (Format?) -> Boolean,
    private val buffer: EmbeddedNarrationPrefetchBuffer,
) : TrackOutput {
    companion object {
        private const val MAX_BUFFER_BYTES = 512 * 1024
    }

    private val cueDecoder = CueDecoder()
    private var format: Format? = null
    private var buffered = ByteArray(0)
    private var start = 0
    private var end = 0

    fun resetBuffer() {
        start = 0
        end = 0
    }

    private fun shouldObserveSample(): Boolean {
        if (!isEnabled()) return false
        val currentFormat = format ?: return false
        if (currentFormat.sampleMimeType != MimeTypes.APPLICATION_MEDIA3_CUES) return false
        return selectedTrackMatcher(currentFormat)
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        this.format = format
        delegate.format(format)
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        if (sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN || !shouldObserveSample()) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        val bytes = ByteArray(length)
        val read = input.read(bytes, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            return if (allowEndOfInput) C.RESULT_END_OF_INPUT else throw EOFException()
        }
        if (read > 0) {
            append(bytes, 0, read)
            delegate.sampleData(ParsableByteArray(bytes, read), read, sampleDataPart)
        }
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && shouldObserveSample()) {
            append(data.data, data.position, length)
        }
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        observe(timeUs, size, offset)
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        if (end > 0) {
            start = (end - offset).coerceIn(start, end)
            if (start == end) {
                start = 0
                end = 0
            }
        }
    }

    private fun observe(timeUs: Long, size: Int, offset: Int) {
        if (timeUs == C.TIME_UNSET || size <= 0 || !shouldObserveSample()) return
        val sampleStart = end - offset - size
        if (sampleStart < start || sampleStart + size > end) return

        val decoded = runCatching { cueDecoder.decode(timeUs, buffered, sampleStart, size) }.getOrNull()
            ?: return
        val cueStartUs = decoded.startTimeUs.takeIf { it != C.TIME_UNSET } ?: timeUs
        val playPosUs = currentPositionUs()
        val text = decoded.cues.mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (text.isBlank()) return

        val windowSec = decoded.durationUs
            .takeIf { it != C.TIME_UNSET && it > 0L }
            ?.let { (it / 1_000_000f).coerceIn(1.0f, 7.0f) }
        buffer.submit(cueStartUs, windowSec, text, format, playPosUs)
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        if (end - start + length > MAX_BUFFER_BYTES) {
            resetBuffer()
            if (length > MAX_BUFFER_BYTES) return
        }
        val retained = end - start
        val required = retained + length
        if (buffered.size - end < length) {
            buffered.copyInto(buffered, 0, start, end)
            start = 0
            end = retained
            if (buffered.size < required) {
                buffered = buffered.copyOf(max(required, max(buffered.size * 2, 256)))
            }
        }
        data.copyInto(buffered, end, offset, offset + length)
        end += length
    }
}
