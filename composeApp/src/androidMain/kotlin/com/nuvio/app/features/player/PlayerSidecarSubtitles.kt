@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.SubtitleView
import com.nuvio.app.R
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

private const val SIDECAR_TAG = "NuvioSidecar"
private val sidecarParserFactory = DefaultSubtitleParserFactory()
private val mainHandler = Handler(Looper.getMainLooper())
private const val SIDECAR_RENDER_INTERVAL_MS = 100L
private const val PREFETCH_AHEAD_US = 300_000_000L
private const val MAX_PREFETCH_CUES = 150
private const val EMPTY_CUE_SIGNATURE = 0x4E5556494FL // "NUVIO"

internal class SidecarSubtitleController(
    private val scope: CoroutineScope,
    private val getPlayer: () -> Player?,
    private val getSubtitleDelayMs: () -> Int = { 0 },
) {
    private var sidecarSubtitleJob: Job? = null
    var activeSidecarSubtitleKey: String? = null
        private set
    /**
     * When true the sidecar only feeds TTS timing; the subtitle is still rendered
     * by Media3/libass (used for .ass with libass on, which fits TTS perfectly).
     */
    var activeSidecarTtsOnly: Boolean = false
        private set
    var sidecarTimedCues: List<CuesWithTiming> = emptyList()
        private set
    private var lastSidecarCueSignature: Long? = null
    private var exoSubtitleViewRef: WeakReference<SubtitleView>? = null

    /** Fired with the currently visible cues whenever they change (e.g. for TTS narration). */
    var onActiveCues: ((cues: List<Cue>, windowSec: Float?, fullWindowSec: Float?) -> Unit)? = null

    /**
     * Fired with upcoming (text, full-duration-window) pairs for TTS prefetch,
     * so the next lines are synthesized before they appear. Deduped by content.
     */
    var onUpcomingCues: ((List<Pair<String, Float>>) -> Unit)? = null
    private var lastUpcomingSignature: Long? = null

    fun isSidecarActive(): Boolean = activeSidecarSubtitleKey != null

    fun canAttachAddonSubtitleViaSidecar(url: String, useLibass: Boolean): Boolean {
        val mime = PlayerSubtitleUtils.mimeTypeFromUrl(url)
        if (mime == MimeTypes.TEXT_SSA && useLibass) {
            return false
        }
        val format = Format.Builder().setSampleMimeType(mime).build()
        return sidecarParserFactory.supportsFormat(format)
    }

    /**
     * True when the parser can derive cue timings for [url] regardless of who
     * renders it. Used to run a TTS-only sidecar alongside Media3/libass.
     */
    fun supportsTimingFor(url: String): Boolean {
        val mime = PlayerSubtitleUtils.mimeTypeFromUrl(url)
        val format = Format.Builder().setSampleMimeType(mime).build()
        return sidecarParserFactory.supportsFormat(format)
    }

    fun bindSubtitleView(subtitleView: SubtitleView?) {
        exoSubtitleViewRef = subtitleView?.let { WeakReference(it) }
        if (subtitleView == null && activeSidecarSubtitleKey != null) {
            return
        }
        if (activeSidecarSubtitleKey != null && sidecarTimedCues.isNotEmpty()) {
            renderSidecarCuesAtCurrentPosition()
        }
    }

    fun stopSidecarAddonSubtitle(clearView: Boolean = true) {
        sidecarSubtitleJob?.cancel()
        sidecarSubtitleJob = null
        val wasRendering = !activeSidecarTtsOnly
        activeSidecarSubtitleKey = null
        activeSidecarTtsOnly = false
        sidecarTimedCues = emptyList()
        lastSidecarCueSignature = null
        if (clearView && wasRendering) {
            postToSubtitleView { view ->
                view.setTag(R.id.player_view_sidecar_generation_tag, null)
                view.setCues(emptyList())
            }
        }
    }

    fun startSidecarAddonSubtitle(
        url: String,
        headers: Map<String, String> = emptyMap(),
        useLibass: Boolean = false,
        ttsOnly: Boolean = false,
    ): Boolean {
        val allowed = if (ttsOnly) supportsTimingFor(url) else canAttachAddonSubtitleViaSidecar(url, useLibass)
        if (!allowed) return false

        val subtitleKey = url
        val urlMimeHint = PlayerSubtitleUtils.mimeTypeFromUrl(url)

        sidecarSubtitleJob?.cancel()
        activeSidecarSubtitleKey = subtitleKey
        activeSidecarTtsOnly = ttsOnly
        lastSidecarCueSignature = null
        lastUpcomingSignature = null
        sidecarTimedCues = emptyList()
        if (!ttsOnly) {
            postToSubtitleView { view ->
                view.setTag(R.id.player_view_sidecar_generation_tag, subtitleKey)
                view.setCues(emptyList())
            }
        }

        sidecarSubtitleJob = scope.launch {
            try {
                val rawBody = withContext(Dispatchers.IO) {
                    httpGetTextWithHeaders(url = url, headers = headers)
                }
                if (activeSidecarSubtitleKey != subtitleKey) return@launch

                val resolvedMime = PlayerSubtitleUtils.sniffSubtitleMimeType(rawBody, url)
                val parseResult = withContext(Dispatchers.Default) {
                    parseSidecarTimedCuesRobust(rawBody, url)
                }
                if (activeSidecarSubtitleKey != subtitleKey) return@launch

                if (parseResult.cues.isEmpty()) {
                    Log.w(
                        SIDECAR_TAG,
                        "Sidecar subtitle parse empty for url=$url urlMime=$urlMimeHint sniffed=$resolvedMime (buffer preserved; no media reload)"
                    )
                    activeSidecarSubtitleKey = null
                    activeSidecarTtsOnly = false
                    sidecarTimedCues = emptyList()
                    if (!ttsOnly) {
                        postToSubtitleView { view ->
                            view.setTag(R.id.player_view_sidecar_generation_tag, null)
                            view.setCues(emptyList())
                        }
                    }
                    return@launch
                }

                sidecarTimedCues = parseResult.cues
                Log.d(
                    SIDECAR_TAG,
                    "Sidecar subtitle ready url=$url cues=${parseResult.cues.size} mime=${parseResult.effectiveMime} source=${parseResult.source} (buffer preserved)"
                )

                while (isActive && activeSidecarSubtitleKey == subtitleKey) {
                    renderSidecarCuesAtCurrentPosition()
                    delay(SIDECAR_RENDER_INTERVAL_MS)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activeSidecarSubtitleKey != subtitleKey) return@launch
                Log.w(
                    SIDECAR_TAG,
                    "Sidecar subtitle failed url=$url: ${e.message} (buffer preserved; no media reload)"
                )
                activeSidecarSubtitleKey = null
                activeSidecarTtsOnly = false
                sidecarTimedCues = emptyList()
                if (!ttsOnly) {
                    postToSubtitleView { view ->
                        view.setTag(R.id.player_view_sidecar_generation_tag, null)
                        view.setCues(emptyList())
                    }
                }
            }
        }
        return true
    }

    fun renderSidecarCuesAtCurrentPosition() {
        val cues = sidecarTimedCues
        if (cues.isEmpty() || activeSidecarSubtitleKey == null) return
        val player = getPlayer() ?: return
        val delayUs = getSubtitleDelayMs().toLong() * 1_000L
        val positionUs = (player.currentPosition.coerceAtLeast(0L) * 1_000L - delayUs).coerceAtLeast(0L)
        publishUpcomingCues(cues, positionUs)
        val active = collectActiveSidecarCues(cues, positionUs)
        val signature = activeCueSignature(active)
        if (signature == lastSidecarCueSignature) return
        lastSidecarCueSignature = signature
        if (active.isNotEmpty()) {
            val budgetSec = untilNextCueStartSec(cues, positionUs)
            val rateWindowSec = activeRateWindowSec(cues, positionUs)
            runCatching { onActiveCues?.invoke(active, budgetSec, rateWindowSec) }
        }
        if (activeSidecarTtsOnly) return
        val currentKey = activeSidecarSubtitleKey ?: return
        postToSubtitleView { view ->
            if (view.getTag(R.id.player_view_sidecar_generation_tag) == currentKey) {
                view.setCues(active)
            }
        }
    }

    private fun publishUpcomingCues(cues: List<CuesWithTiming>, positionUs: Long) {
        val upcoming = ArrayList<Pair<String, Float>>(2)
        for (index in cues.indices) {
            val entry = cues[index]
            if (entry.startTimeUs <= positionUs) continue
            if (entry.startTimeUs - positionUs > PREFETCH_AHEAD_US) break
            val text = TtsNarrationController.combineCueTexts(entry.cues) ?: continue
            // Speech slot = this cue's start -> the NEXT cue's start. The rate is
            // chosen to fit that slot (may exceed this cue's own end time, using
            // the gap, but must not run past the next cue's start).
            val next = nextCueStartUs(cues, entry.startTimeUs, index + 1)
            val window = if (next != null && next > entry.startTimeUs) {
                (next - entry.startTimeUs) / 1_000_000f
            } else {
                0f
            }
            upcoming.add(text to window)
            if (upcoming.size >= MAX_PREFETCH_CUES) break
        }
        if (upcoming.isEmpty()) return
        var signature = upcoming.size.toLong()
        for ((text, window) in upcoming) {
            signature = 31L * signature + text.hashCode()
            signature = 31L * signature + window.toBits().toLong()
        }
        if (signature == lastUpcomingSignature) return
        lastUpcomingSignature = signature
        runCatching { onUpcomingCues?.invoke(upcoming) }
    }

    private fun postToSubtitleView(block: (SubtitleView) -> Unit) {
        val view = exoSubtitleViewRef?.get() ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block(view)
        } else {
            mainHandler.post {
                exoSubtitleViewRef?.get()?.let(block)
            }
        }
    }
}

internal data class SidecarParseResult(
    val cues: List<CuesWithTiming>,
    val effectiveMime: String,
    val source: String
)

internal fun parseSidecarTimedCuesRobust(rawText: String, sourceUrl: String): SidecarParseResult {
    val cleaned = rawText.replace("\uFEFF", "")
    val candidates = PlayerSubtitleUtils.sidecarMimeCandidates(cleaned, sourceUrl)
    for (mime in candidates) {
        val parsed = parseSidecarTimedCuesWithMime(cleaned, mime)
        if (parsed.isNotEmpty()) {
            val fixed = PlayerSubtitleRtlFix.fixTimedCues(parsed, isBuiltInSubtitle = false)
            val normalized = if (mime == MimeTypes.TEXT_VTT) normalizeTimedCuePositions(fixed) else fixed
            return SidecarParseResult(
                normalized,
                mime,
                source = "media3"
            )
        }
    }

    val sniffedMime = PlayerSubtitleUtils.sniffSubtitleMimeType(cleaned, sourceUrl)
    val lenient = if (sniffedMime == MimeTypes.TEXT_SSA || sniffedMime == MimeTypes.APPLICATION_TTML) {
        emptyList()
    } else {
        parseSidecarTimedCuesLenient(cleaned, sourceUrl)
    }
    if (lenient.isNotEmpty()) {
        val fixed = PlayerSubtitleRtlFix.fixTimedCues(lenient, isBuiltInSubtitle = false)
        val normalized = if (sniffedMime == MimeTypes.TEXT_VTT) normalizeTimedCuePositions(fixed) else fixed
        return SidecarParseResult(
            normalized,
            sniffedMime,
            source = "lenient"
        )
    }

    return SidecarParseResult(
        cues = emptyList(),
        effectiveMime = candidates.firstOrNull() ?: MimeTypes.APPLICATION_SUBRIP,
        source = "none"
    )
}

private fun normalizeTimedCuePositions(cues: List<CuesWithTiming>): List<CuesWithTiming> {
    return cues.map { entry ->
        val normalized = entry.cues.map { normalizeSidecarCuePosition(it) }
        val durationUs = when {
            entry.durationUs != C.TIME_UNSET -> entry.durationUs
            entry.endTimeUs != C.TIME_UNSET && entry.startTimeUs != C.TIME_UNSET ->
                (entry.endTimeUs - entry.startTimeUs).coerceAtLeast(1L)
            else -> C.TIME_UNSET
        }
        CuesWithTiming(normalized, entry.startTimeUs, durationUs)
    }
}

private fun parseSidecarTimedCuesWithMime(rawText: String, mimeType: String): List<CuesWithTiming> {
    val format = Format.Builder().setSampleMimeType(mimeType).build()
    if (!sidecarParserFactory.supportsFormat(format)) return emptyList()
    return try {
        val parser = sidecarParserFactory.create(format)
        val data = rawText.toByteArray(Charsets.UTF_8)
        val out = ArrayList<CuesWithTiming>(256)
        parser.parse(data, SubtitleParser.OutputOptions.allCues()) { cueGroup ->
            if (cueGroup.startTimeUs != C.TIME_UNSET) {
                out.add(cueGroup)
            }
        }
        out.sortBy { it.startTimeUs }
        out
    } catch (e: Exception) {
        Log.d(
            SIDECAR_TAG,
            "Sidecar Media3 parse failed mime=$mimeType: ${e.message}"
        )
        emptyList()
    }
}

internal fun parseSidecarTimedCuesLenient(rawText: String, sourceUrl: String): List<CuesWithTiming> {
    val syncCues = try {
        PlayerSubtitleCueParser.parseFromText(rawText, sourceUrl)
            .filter { it.text.isNotBlank() && it.startTimeMs >= 0L }
    } catch (_: Exception) {
        emptyList()
    }
    if (syncCues.isEmpty()) return emptyList()

    val out = ArrayList<CuesWithTiming>(syncCues.size)
    for (i in syncCues.indices) {
        val startUs = syncCues[i].startTimeMs * 1_000L
        val endUs = (syncCues[i].endTimeMs * 1_000L).coerceAtLeast(startUs + 1L)
        val durationUs = (endUs - startUs).coerceAtLeast(1L)
        val cue = Cue.Builder().setText(syncCues[i].text).build()
        out.add(CuesWithTiming(listOf(cue), startUs, durationUs))
    }
    return out
}

/** Start time (us) of the next cue strictly after [afterStartUs], or null. */
private fun nextCueStartUs(
    cues: List<CuesWithTiming>,
    afterStartUs: Long,
    fromIndex: Int,
): Long? {
    var i = fromIndex
    while (i < cues.size) {
        if (cues[i].startTimeUs > afterStartUs) return cues[i].startTimeUs
        i++
    }
    return null
}

/** Index of the last cue that has already started at [positionUs], or -1. */
private fun activeEntryIndex(cues: List<CuesWithTiming>, positionUs: Long): Int {
    var idx = -1
    for (i in cues.indices) {
        if (cues[i].startTimeUs <= positionUs) idx = i else break
    }
    return idx
}

/**
 * Seconds from now until the next cue's start: the hard stop for the current
 * line. Narration may run past the current cue's own end (using the gap) but
 * must never spill into the next cue. Null when there is no following cue.
 */
private fun untilNextCueStartSec(
    cues: List<CuesWithTiming>,
    positionUs: Long,
): Float? {
    val i = activeEntryIndex(cues, positionUs)
    if (i < 0) return null
    val next = nextCueStartUs(cues, cues[i].startTimeUs, i + 1) ?: return null
    return ((next - positionUs) / 1_000_000f).coerceAtLeast(0f)
}

/**
 * Speech slot for the active cue = its start -> the NEXT cue's start. The rate is
 * chosen to fit this slot, so the prefetch pass and the speak pass pick the exact
 * same rate and hit the cache (see publishUpcomingCues). Null -> natural rate.
 */
private fun activeRateWindowSec(
    cues: List<CuesWithTiming>,
    positionUs: Long,
): Float? {
    val i = activeEntryIndex(cues, positionUs)
    if (i < 0) return null
    val next = nextCueStartUs(cues, cues[i].startTimeUs, i + 1) ?: return null
    val d = next - cues[i].startTimeUs
    return if (d > 0) d / 1_000_000f else null
}

private fun collectActiveSidecarCues(    cues: List<CuesWithTiming>,
    positionUs: Long
): List<Cue> {
    if (cues.isEmpty()) return emptyList()
    val active = ArrayList<Cue>(4)
    for (entry in cues) {
        if (entry.startTimeUs > positionUs) break
        val end = when {
            entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
            entry.durationUs != C.TIME_UNSET -> entry.startTimeUs + entry.durationUs
            else -> Long.MAX_VALUE
        }
        if (positionUs < end) {
            active.addAll(entry.cues)
        }
    }
    return active
}

private fun activeCueSignature(cues: List<Cue>): Long {
    if (cues.isEmpty()) return EMPTY_CUE_SIGNATURE
    var hash = cues.size.toLong()
    for (cue in cues) {
        hash = 31L * hash + (cue.text?.hashCode()?.toLong() ?: 0L)
        hash = 31L * hash + cue.line.toBits().toLong()
        hash = 31L * hash + cue.position.toBits().toLong()
        hash = 31L * hash + cue.lineAnchor.toLong()
        hash = 31L * hash + cue.positionAnchor.toLong()
        hash = 31L * hash + cue.size.toBits().toLong()
    }
    return hash
}

private fun normalizeSidecarCuePosition(cue: Cue): Cue {
    if (cue.bitmap != null || cue.verticalType != Cue.TYPE_UNSET || cue.line == Cue.DIMEN_UNSET) {
        return cue
    }
    return cue.buildUpon()
        .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
        .setLineAnchor(Cue.TYPE_UNSET)
        .build()
}
