package com.nuvio.app.features.player

data class PlayerMediaInfo(
    val videoCodec: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFrameRate: Float? = null,
    val videoBitrate: Int? = null,
    val videoDynamicRange: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
)

internal fun mpvMediaInfo(property: (String) -> String?): PlayerMediaInfo =
    PlayerMediaInfo(
        videoCodec = property("current-tracks/video/codec")?.let(::mpvCodecLabel),
        videoWidth = property("video-params/w")?.toIntOrNull()?.takeIf { it > 0 },
        videoHeight = property("video-params/h")?.toIntOrNull()?.takeIf { it > 0 },
        videoFrameRate = property("container-fps")?.toFloatOrNull()?.takeIf { it > 0f },
        videoBitrate = property("video-bitrate")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
        videoDynamicRange = detectDynamicRange(
            gamma = property("video-params/gamma"),
            primaries = property("video-params/primaries"),
        ),
        audioCodec = property("current-tracks/audio/codec")?.let(::mpvCodecLabel),
        audioChannels = property("audio-params/channel-count")?.toIntOrNull()?.takeIf { it > 0 },
        audioSampleRate = property("audio-params/samplerate")?.toIntOrNull()?.takeIf { it > 0 },
    )

private fun detectDynamicRange(gamma: String?, primaries: String?): String? {
    val g = gamma?.trim()?.lowercase()
    val p = primaries?.trim()?.lowercase()
    return when {
        g != null && g.contains("pq") -> "HDR10"
        g != null && g.contains("hlg") -> "HLG"
        p != null && p.contains("bt.2020") -> "HDR"
        g != null && (g.contains("srgb") || g.contains("bt.709")) -> "SDR"
        else -> null
    }
}

private fun mpvCodecLabel(codec: String): String? =
    when (codec.trim().lowercase()) {
        "" -> null
        "h264" -> "AVC"
        "ac3" -> "AC-3"
        "eac3" -> "E-AC-3"
        "truehd" -> "TrueHD"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        else -> codec.trim().uppercase()
    }
